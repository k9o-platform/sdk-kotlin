package dev.k9o.gate

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class K9OGateTest {
    private lateinit var server: MockWebServer
    private lateinit var gate: GateClient
    private lateinit var store: InMemoryTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        // Sign-in asks the locate door first; these tests are about the call after it (LocateTest covers the door).
        server.dispatcher = object : QueueDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/gate/locate") == true) {
                    MockResponse().setBody("""{"data":{"region":"de01","origin":null},"meta":{}}""")
                } else {
                    super.dispatch(request)
                }
        }
        server.start()
        store = InMemoryTokenStore()
        gate = GateClient(
            orgBaseUrl = server.url("").toString(),
            projectSlug = "test-project",
            tokenStore = store,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The next request that is not the locate door's. */
    private fun takeCredentialRequest(): RecordedRequest {
        while (true) {
            val r = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            if (r.path?.endsWith("/gate/locate") != true) return r
        }
    }

    // MARK: - signIn

    @Test
    fun `signIn posts email and password only and returns user and session`() = runTest {
        server.enqueue(MockResponse().setBody(authResultJson()).setResponseCode(200))
        val result = gate.signIn("user@example.com", "pass")
        assertEquals("eu_TestUserABC123", result.user.id)
        assertEquals("user@example.com", result.user.email)
        assertEquals("2026-01-01T00:00:00Z", result.user.createdAt)
        assertEquals("rt_MockRefreshToken0001", result.session.refreshToken)
        assertEquals("2099-01-01T00:00:00Z", result.session.expiresAt)
        val recorded = takeCredentialRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/signin", recorded.path)
        assertEquals(
            mapOf("email" to "user@example.com", "password" to "pass"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `signIn persists refresh token to store`() = runTest {
        server.enqueue(MockResponse().setBody(authResultJson()).setResponseCode(200))
        gate.signIn("user@example.com", "pass")
        assertEquals("rt_MockRefreshToken0001", store.load())
    }

    // MARK: - refreshToken

    @Test
    fun `refreshToken parses the result and stores the next refresh token`() = runTest {
        store.save("rt_InitialToken")
        server.enqueue(MockResponse().setBody(refreshJson()).setResponseCode(200))
        val result = gate.refreshToken()
        assertEquals("access.jwt.value", result.accessToken)
        assertEquals("2099-01-01T00:00:00Z", result.expiresAt)
        assertEquals("rt_NextToken0002", result.refreshToken)
        assertEquals("rt_NextToken0002", store.load())
        val recorded = takeCredentialRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/refresh", recorded.path)
        // The body is closed to { refreshToken }: the gateway refuses any other field.
        assertEquals(
            mapOf("refreshToken" to "rt_InitialToken"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `refreshToken with a token that is not the stored one leaves the store alone`() = runTest {
        store.save("rt_Stored")
        server.enqueue(MockResponse().setBody(refreshJson()).setResponseCode(200))
        gate.refreshToken(refreshToken = "rt_Other")
        assertEquals("rt_Stored", store.load())
    }

    @Test
    fun `concurrent refreshes share one request and the next refresh sends the stored next token`() = runTest {
        store.save("rt_Initial")
        server.enqueue(MockResponse().setBody(refreshJson()).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody(refreshJson()))
        val results = withContext(Dispatchers.IO) { (1..3).map { async { gate.refreshToken() } }.awaitAll() }
        assertTrue(results.all { it.refreshToken == "rt_NextToken0002" })
        assertTrue(takeCredentialRequest().body.readUtf8().contains("rt_Initial"))
        gate.refreshToken()
        // One request went out for the three callers, so this one is the second and carries the stored next token.
        assertTrue(takeCredentialRequest().body.readUtf8().contains("rt_NextToken0002"))
    }

    @Test
    fun `signOut waits for a refresh in flight and signs out the next token`() = runTest {
        store.save("rt_Initial")
        server.enqueue(MockResponse().setBody(refreshJson()).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        server.enqueue(MockResponse().setBody("""{"data":{"revoked":true},"meta":{}}"""))
        withContext(Dispatchers.IO) {
            val refreshing = async { gate.refreshToken() }
            delay(100)
            val signingOut = async { gate.signOut() }
            refreshing.await()
            signingOut.await()
        }
        assertTrue(takeCredentialRequest().body.readUtf8().contains("rt_Initial"))
        assertTrue(takeCredentialRequest().body.readUtf8().contains("rt_NextToken0002"))
        assertNull(store.load())
    }

    @Test
    fun `a session that ended clears the store and exposes the reason`() = runTest {
        for (reason in listOf("replay", "revoked", "inactive", "max_length")) {
            store.save("rt_Old")
            server.enqueue(MockResponse().setResponseCode(401).setBody(sessionExpiredJson(reason)))
            val e = try { gate.refreshToken(); null } catch (e: GateError.Unauthenticated) { e }
            assertEquals(reason, e!!.sessionEndReason)
            assertEquals(401, e.status)
            assertNull(store.load())
        }
    }

    @Test
    fun `an unknown refresh token clears the store`() = runTest {
        store.save("rt_Unknown")
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_CREDENTIALS")).setResponseCode(401))
        try { gate.refreshToken(); fail() } catch (_: GateError.InvalidCredentials) {}
        assertNull(store.load())
    }

    @Test
    fun `a network failure keeps the stored token`() = runTest {
        store.save("rt_Keep")
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
        try { gate.refreshToken(); fail() } catch (_: GateError.Network) {}
        assertEquals("rt_Keep", store.load())
    }

    @Test
    fun `sessionEndReason is kept when errors are redacted and null for other errors`() = runTest {
        val redacted = GateClient(orgBaseUrl = server.url("").toString(), projectSlug = "test-project", tokenStore = store, errors = "redacted")
        store.save("rt_Old")
        server.enqueue(MockResponse().setResponseCode(401).setBody(sessionExpiredJson("replay")))
        val e = try { redacted.refreshToken(); null } catch (e: GateError.Unauthenticated) { e }
        assertEquals("replay", e!!.sessionEndReason)
        assertTrue(e.context.isEmpty())
        assertNull(noSessionError(false).sessionEndReason)
    }

    @Test
    fun `refreshToken explicit token overrides store`() = runTest {
        store.save("rt_ShouldNotBeUsed")
        server.enqueue(MockResponse().setBody(refreshJson()).setResponseCode(200))
        gate.refreshToken(refreshToken = "rt_Explicit")
        val body = takeCredentialRequest().body.readUtf8()
        assertTrue(body.contains("rt_Explicit"))
        assertFalse(body.contains("rt_ShouldNotBeUsed"))
    }

    // MARK: - signOut

    @Test
    fun `signOut sends the refresh token, reports revoked and clears the store`() = runTest {
        store.save("rt_ToRevoke")
        server.enqueue(MockResponse().setBody("""{"data":{"revoked":true},"meta":{}}""").setResponseCode(200))
        assertTrue(gate.signOut())
        assertNull(store.load())
        val recorded = takeCredentialRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/gate/signout", recorded.path)
        assertEquals(
            mapOf("refreshToken" to "rt_ToRevoke"),
            Gson().fromJson(recorded.body.readUtf8(), Map::class.java),
        )
    }

    @Test
    fun `signOut of an unknown token returns false and still clears the store`() = runTest {
        store.save("rt_Unknown")
        server.enqueue(MockResponse().setBody("""{"data":{"revoked":false},"meta":{}}""").setResponseCode(200))
        assertFalse(gate.signOut())
        assertNull(store.load())
    }

    // MARK: - revokeAllSessions

    @Test
    fun `revokeAllSessions posts with the access token, returns the count and clears the store`() = runTest {
        store.save("rt_ToRevoke")
        server.enqueue(MockResponse().setBody("""{"data":{"revokedCount":3},"meta":{}}""").setResponseCode(200))
        assertEquals(3, gate.revokeAllSessions("access.jwt.value"))
        assertNull(store.load())
        val recorded = takeCredentialRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/@test-project/auth/sessions/revoke-all", recorded.path)
        assertEquals("Bearer access.jwt.value", recorded.getHeader("Authorization"))
        assertEquals(0L, recorded.bodySize)
    }

    @Test
    fun `revokeAllSessions refused is a GateError and keeps the store`() = runTest {
        for (code in listOf("K9O_TRUST_ZONE_VIOLATION", "K9O_PROJECT_SCOPE_MISMATCH")) {
            store.save("rt_Keep")
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setBody("""{"code":"$code","message":"refused","context":{}}"""),
            )
            val e = try {
                gate.revokeAllSessions("operator.token")
                fail("expected a GateError")
                return@runTest
            } catch (e: GateError) {
                e
            }
            assertEquals(code, e.code)
            assertEquals(403, e.status)
            assertEquals("rt_Keep", store.load())
        }
    }

    @Test
    fun `signOut is no-op when no token`() = runTest {
        // No token → no network request; if server gets a request the test fails
        assertFalse(gate.signOut())
        assertEquals(0, server.requestCount)
    }

    // MARK: - Error cases

    @Test(expected = GateError.InvalidCredentials::class)
    fun `signIn throws InvalidCredentials on INVALID_CREDENTIALS`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_CREDENTIALS")).setResponseCode(401))
        gate.signIn("bad@example.com", "wrong")
    }

    @Test(expected = GateError.Unauthenticated::class)
    fun `refreshToken throws Unauthenticated when no token in store`() = runTest {
        gate.refreshToken()
    }

    @Test
    fun `non-JSON 200 body throws Decoding`() = runTest {
        server.enqueue(MockResponse().setBody("not-json").setResponseCode(200))
        try {
            gate.signIn("u@e.com", "p")
            fail("Expected GateError.Decoding")
        } catch (e: GateError.Decoding) {
            // pass
        }
    }

    @Test
    fun `500 error maps to GateError Unknown`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INTERNAL_ERROR")).setResponseCode(500))
        try {
            gate.signIn("u@e.com", "p")
            fail("Expected GateError.Unknown")
        } catch (e: GateError.Unknown) {
            assertEquals(500, e.statusCode)
        }
    }

    // MARK: - AccessToken JWT decode

    @Test
    fun `AccessToken decode reads flat custom claims`() {
        // Payload: {"sub":"eu_TestUserABC123","exp":9999999999,"iat":1700000000,"iss":"https://issuer.example",
        //           "ext.partners.partner_role":"extension_org_admin"}  (flat key, not a nested object)
        val payload = """{"sub":"eu_TestUserABC123","exp":9999999999,"iat":1700000000,"iss":"https://issuer.example","ext.partners.partner_role":"extension_org_admin"}"""
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        val token = AccessToken.decode("eyJhbGciOiJFUzI1NiJ9.$b64.sig")
        assertNotNull(token)
        assertEquals("eu_TestUserABC123", token!!.subject)
        assertEquals("https://issuer.example", token.issuer)
        assertEquals("extension_org_admin", token.customClaims["ext.partners.partner_role"])
        assertEquals("extension_org_admin", token.customClaim("partners", "partner_role"))
        assertEquals(1, token.customClaims.size)
        assertNull(token.customClaim("partners", "missing"))
    }

    @Test
    fun `AccessToken decode returns null for malformed JWT`() {
        assertNull(AccessToken.decode("not.a.jwt.at.all.whatsoever"))
        assertNull(AccessToken.decode("only.two"))
        assertNull(AccessToken.decode(""))
    }

    // MARK: - Token store roundtrip

    @Test
    fun `InMemoryTokenStore save load delete`() {
        val s = InMemoryTokenStore()
        assertNull(s.load())
        s.save("rt_Token123")
        assertEquals("rt_Token123", s.load())
        s.delete()
        assertNull(s.load())
    }
}

// MARK: - Helpers

class InMemoryTokenStore : TokenStore {
    private var token: String? = null
    override fun save(token: String) { this.token = token }
    override fun load(): String? = token
    override fun delete() { token = null }
}

fun authResultJson() = """
{
  "data": {
    "user": {
      "id": "eu_TestUserABC123",
      "email": "user@example.com",
      "status": "active",
      "createdAt": "2026-01-01T00:00:00Z",
      "updatedAt": null,
      "lastSigninAt": null,
      "roles": ["PROJECT_END_USER"],
      "groups": []
    },
    "session": {
      "accessToken": "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJldV9UZXN0VXNlckFCQzEyMyIsImV4cCI6OTk5OTk5OTk5OSwiaWF0IjoxNzAwMDAwMDAwLCJpc3MiOiJodHRwczovL2FwaS5rOW8uaW8iLCJleHQucGFydG5lcnMiOnsicGFydG5lcl9yb2xlIjoiZXh0ZW5zaW9uX29yZ19hZG1pbiJ9fQ.sig",
      "refreshToken": "rt_MockRefreshToken0001",
      "expiresAt": "2099-01-01T00:00:00Z"
    }
  },
  "meta": {}
}
""".trimIndent()

fun refreshJson() = """{"data":{"accessToken":"access.jwt.value","refreshToken":"rt_NextToken0002","expiresAt":"2099-01-01T00:00:00Z"},"meta":{}}"""

fun sessionExpiredJson(reason: String) = """{"code":"K9O_SESSION_EXPIRED","message":"x","context":{"reason":"$reason"},"recommendation":null}"""

fun errorJson(code: String) = """{"code":"$code","message":"Error","context":{},"recommendation":null}"""
