package dev.k9o.gate

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The end-user flows: passkeys, authenticator apps, password reset, invitations and session checks. */
class GateFlowsTest {
    private lateinit var server: MockWebServer
    private lateinit var gate: GateClient

    @Before
    fun setUp() {
        server = MockWebServer()
        // password-reset/request asks the locate door first (LocateTest covers the door).
        server.dispatcher = object : QueueDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/gate/locate") == true) {
                    MockResponse().setBody("""{"data":{"region":"de01","origin":null},"meta":{}}""")
                } else {
                    super.dispatch(request)
                }
        }
        server.start()
        gate = GateClient(
            orgBaseUrl = server.url("").toString(),
            projectSlug = "test-project",
            tokenStore = null,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun next(): RecordedRequest {
        while (true) {
            val r = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)!!
            if (r.path?.endsWith("/gate/locate") != true) return r
        }
    }

    private fun body(r: RecordedRequest): Map<*, *> = Gson().fromJson(r.body.readUtf8(), Map::class.java)

    private fun ok(data: String) = MockResponse().setBody("""{"data":$data,"meta":{}}""").setResponseCode(200)

    private val sessionJson =
        """{"accessToken":"access.jwt.value","refreshToken":"rt_Next0001","expiresAt":"2099-01-01T00:00:00Z"}"""

    // MARK: - passkeys

    @Test
    fun `register begin posts with the bearer and no body and returns the options`() = runTest {
        server.enqueue(ok("""{"challenge":"ch_abc","rp":{"id":"example.test"},"user":{"id":"u1"}}"""))
        val options = gate.passkeys.register.begin("access.jwt.value")
        assertEquals("ch_abc", options.challenge)
        val parsed = Gson().fromJson(options.json, Map::class.java)
        assertEquals("ch_abc", parsed["challenge"])
        assertEquals(mapOf("id" to "example.test"), parsed["rp"])
        val r = next()
        assertEquals("POST", r.method)
        assertEquals("/@test-project/gate/passkeys/register/begin", r.path)
        assertEquals("Bearer access.jwt.value", r.getHeader("Authorization"))
        assertEquals(0L, r.bodySize)
    }

    @Test
    fun `register finish sends the credential as response with the label`() = runTest {
        server.enqueue(ok("""{"passkey":{"id":"pk_1","credentialId":"cred_1"}}"""))
        val result = gate.passkeys.register.finish("""{"id":"cred_1","response":{"attestationObject":"x"}}""", "access.jwt.value", "Pixel")
        assertEquals("pk_1", result.passkey.id)
        assertEquals("cred_1", result.passkey.credentialId)
        val r = next()
        assertEquals("/@test-project/gate/passkeys/register/finish", r.path)
        assertEquals("Bearer access.jwt.value", r.getHeader("Authorization"))
        assertEquals(
            mapOf("response" to mapOf("id" to "cred_1", "response" to mapOf("attestationObject" to "x")), "label" to "Pixel"),
            body(r),
        )
    }

    @Test
    fun `register finish omits the label when none is given`() = runTest {
        server.enqueue(ok("""{"passkey":{"id":"pk_1","credentialId":"cred_1"}}"""))
        gate.passkeys.register.finish("""{"id":"c","response":{}}""", "access.jwt.value")
        assertEquals(setOf("response"), body(next()).keys)
    }

    @Test
    fun `finish refuses a credential that is not a JSON object before sending anything`() = runTest {
        for (bad in listOf("not json", "[1]", "\"x\"")) {
            try {
                gate.passkeys.register.finish(bad, "t")
                fail("expected a K9OGateConfigException for $bad")
            } catch (e: K9OGateConfigException) {
                assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signIn begin sends no bearer and the email when given`() = runTest {
        server.enqueue(ok("""{"challenge":"ch_si","allowCredentials":[]}"""))
        val options = gate.passkeys.signIn.begin("user@example.com")
        assertEquals("ch_si", options.challenge)
        val r = next()
        assertEquals("/@test-project/gate/passkeys/signin/begin", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(mapOf("email" to "user@example.com"), body(r))
    }

    @Test
    fun `signIn begin with no email sends an empty object`() = runTest {
        server.enqueue(ok("""{"challenge":"ch_si"}"""))
        gate.passkeys.signIn.begin()
        assertEquals(emptyMap<String, Any>(), body(next()))
    }

    @Test
    fun `signIn finish sends the assertion with no bearer and returns the session`() = runTest {
        server.enqueue(ok("""{"session":$sessionJson}"""))
        val result = gate.passkeys.signIn.finish("""{"id":"cred_1","response":{"signature":"s"}}""")
        assertEquals("rt_Next0001", result.session.refreshToken)
        val r = next()
        assertEquals("/@test-project/gate/passkeys/signin/finish", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(mapOf("response" to mapOf("id" to "cred_1", "response" to mapOf("signature" to "s"))), body(r))
    }

    @Test
    fun `signIn finish maps a refused assertion to GateError`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_FIELD_VALUE")).setResponseCode(400))
        try {
            gate.passkeys.signIn.finish("""{"id":"c","response":{}}""")
            fail()
        } catch (e: GateError.Unknown) {
            assertEquals(400, e.statusCode)
            assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        }
    }

    // MARK: - TOTP

    @Test
    fun `totp enroll sends the bearer and the label and returns the factor`() = runTest {
        server.enqueue(ok("""{"factor":{"id":"fac_1","secret":"JBSWY3DPEHPK3PXP","uri":"otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"}}"""))
        val result = gate.mfa.totp.enroll("access.jwt.value", "Phone")
        assertEquals("fac_1", result.factor.id)
        assertEquals("JBSWY3DPEHPK3PXP", result.factor.secret)
        assertTrue(result.factor.uri.startsWith("otpauth://"))
        val r = next()
        assertEquals("/@test-project/gate/mfa/totp/enroll", r.path)
        assertEquals("Bearer access.jwt.value", r.getHeader("Authorization"))
        assertEquals(mapOf("label" to "Phone"), body(r))
    }

    @Test
    fun `totp enroll with no label sends an empty object`() = runTest {
        server.enqueue(ok("""{"factor":{"id":"fac_1","secret":"S","uri":"otpauth://x"}}"""))
        gate.mfa.totp.enroll("t")
        assertEquals(emptyMap<String, Any>(), body(next()))
    }

    @Test
    fun `totp confirm sends factorId and code, and currentCode only when given`() = runTest {
        server.enqueue(ok("""{"factor":{"id":"fac_1","verifiedAt":"2026-11-13T00:00:00Z"},"session":$sessionJson}"""))
        val first = gate.mfa.totp.confirm("fac_1", "123456", "access.jwt.value")
        assertEquals("2026-11-13T00:00:00Z", first.factor.verifiedAt)
        assertEquals("rt_Next0001", first.session?.refreshToken)
        val r1 = next()
        assertEquals("/@test-project/gate/mfa/totp/confirm", r1.path)
        assertEquals("Bearer access.jwt.value", r1.getHeader("Authorization"))
        assertEquals(mapOf("factorId" to "fac_1", "code" to "123456"), body(r1))

        server.enqueue(ok("""{"factor":{"id":"fac_2","verifiedAt":"2026-11-13T00:00:01Z"}}"""))
        val second = gate.mfa.totp.confirm("fac_2", "654321", "access.jwt.value", "111111")
        assertNull(second.session)
        assertEquals(mapOf("factorId" to "fac_2", "code" to "654321", "currentCode" to "111111"), body(next()))
    }

    @Test
    fun `totp confirm maps a wrong code to InvalidCredentials`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_CREDENTIALS")).setResponseCode(401))
        try {
            gate.mfa.totp.confirm("fac_1", "000000", "t")
            fail()
        } catch (_: GateError.InvalidCredentials) {
        }
    }

    // MARK: - mfa methods

    @Test
    fun `mfa methods list sends a GET with the bearer and returns the methods`() = runTest {
        server.enqueue(
            ok(
                """{"methods":[{"id":"eumf_1","type":"totp","label":"Phone","verifiedAt":"2026-11-13T00:00:00Z","createdAt":"2026-11-12T00:00:00Z"},""" +
                    """{"id":"eumf_2","type":"totp","label":null,"verifiedAt":null,"createdAt":"2026-11-13T00:00:00Z"}]}""",
            ),
        )
        val result = gate.mfa.methods.list("access.jwt.value")
        assertEquals(2, result.methods.size)
        assertEquals("eumf_1", result.methods[0].id)
        assertEquals("totp", result.methods[0].type)
        assertEquals("Phone", result.methods[0].label)
        assertEquals("2026-11-13T00:00:00Z", result.methods[0].verifiedAt)
        assertNull(result.methods[1].label)
        assertNull(result.methods[1].verifiedAt)
        val r = next()
        assertEquals("GET", r.method)
        assertEquals("/@test-project/gate/mfa/methods", r.path)
        assertEquals("Bearer access.jwt.value", r.getHeader("Authorization"))
    }

    @Test
    fun `mfa methods list maps a missing bearer to Unauthenticated`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_UNAUTHENTICATED")).setResponseCode(401))
        try {
            gate.mfa.methods.list("t")
            fail()
        } catch (_: GateError.Unauthenticated) {
        }
    }

    @Test
    fun `mfa methods delete sends DELETE with the bearer and the code only when given`() = runTest {
        server.enqueue(ok("""{"deleted":true}"""))
        val result = gate.mfa.methods.delete("eumf_1", "access.jwt.value", "123456")
        assertTrue(result.deleted)
        val r1 = next()
        assertEquals("DELETE", r1.method)
        assertEquals("/@test-project/gate/mfa/methods/eumf_1", r1.path)
        assertEquals("Bearer access.jwt.value", r1.getHeader("Authorization"))
        assertEquals(mapOf("code" to "123456"), body(r1))

        server.enqueue(ok("""{"deleted":false}"""))
        val second = gate.mfa.methods.delete("eumf_2", "access.jwt.value")
        assertFalse(second.deleted)
        val r2 = next()
        assertEquals("DELETE", r2.method)
        assertEquals(0L, r2.bodySize)
    }

    @Test
    fun `mfa methods delete encodes the method id as one path segment`() = runTest {
        server.enqueue(ok("""{"deleted":true}"""))
        gate.mfa.methods.delete("a/b c", "t")
        assertEquals("/@test-project/gate/mfa/methods/a%2Fb%20c", next().path)
    }

    @Test
    fun `mfa methods delete maps a wrong code to InvalidCredentials`() = runTest {
        server.enqueue(MockResponse().setBody(errorJson("K9O_INVALID_CREDENTIALS")).setResponseCode(401))
        try {
            gate.mfa.methods.delete("eumf_1", "t", "000000")
            fail()
        } catch (_: GateError.InvalidCredentials) {
        }
    }

    // MARK: - password reset

    @Test
    fun `password reset request locates the email first, then posts it with no bearer`() = runTest {
        server.enqueue(ok("""{"reset":{"requested":true,"expiresAt":"2026-11-13T01:00:00Z"}}"""))
        val result = gate.passwordReset.request("user@example.com")
        assertTrue(result.reset.requested)
        assertEquals("2026-11-13T01:00:00Z", result.reset.expiresAt)
        assertNull(result.debugToken)
        val r = next()
        assertEquals("/@test-project/gate/password-reset/request", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(mapOf("email" to "user@example.com"), body(r))
    }

    @Test
    fun `password reset redeem returns a session`() = runTest {
        server.enqueue(ok("""{"redeemed":true,"endUserId":"eu_1","session":$sessionJson}"""))
        val result = gate.passwordReset.redeem("tok", "N3w-password!")
        assertTrue(result.redeemed)
        assertEquals("eu_1", result.endUserId)
        assertEquals("rt_Next0001", result.session?.refreshToken)
        assertNull(result.mfa)
        val r = next()
        assertEquals("/@test-project/gate/password-reset/redeem", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(mapOf("token" to "tok", "newPassword" to "N3w-password!"), body(r))
    }

    @Test
    fun `password reset redeem returns an mfa challenge and no session when the account has an authenticator`() = runTest {
        server.enqueue(
            ok(
                """{"redeemed":true,"endUserId":"eu_1","mfa":{"required":true,"challengeId":"ch_1","expiresAt":"2026-11-13T00:05:00Z",""" +
                    """"methodsAvailable":["totp"],"resident":{"region":"de01","origin":"https://acme.api-de01.example.test"}}}""",
            ),
        )
        val result = gate.passwordReset.redeem("tok", "N3w-password!")
        assertNull(result.session)
        assertEquals("ch_1", result.mfa?.challengeId)
        assertEquals(listOf("totp"), result.mfa?.methodsAvailable)
        assertEquals("de01", result.mfa?.resident?.region)
    }

    // MARK: - invitations

    @Test
    fun `invitation redeem sends token and password, and the display name when given`() = runTest {
        server.enqueue(ok("""{"redeemed":true,"user":{"id":"eu_9","email":"new@example.com"},"session":$sessionJson}"""))
        val result = gate.invitations.redeem("inv_tok", "pw-12345678", "New Person")
        assertEquals("eu_9", result.user.id)
        assertEquals("new@example.com", result.user.email)
        assertEquals("rt_Next0001", result.session.refreshToken)
        val r = next()
        assertEquals("/@test-project/gate/invitations/redeem", r.path)
        assertNull(r.getHeader("Authorization"))
        assertEquals(mapOf("token" to "inv_tok", "password" to "pw-12345678", "displayName" to "New Person"), body(r))

        server.enqueue(ok("""{"redeemed":true,"user":{"id":"eu_9","email":"new@example.com"},"session":$sessionJson}"""))
        gate.invitations.redeem("inv_tok", "pw-12345678")
        assertEquals(setOf("token", "password"), body(next()).keys)
    }

    // MARK: - verifySession

    @Test
    fun `verifySession sends the bearer and the token, and returns the claims`() = runTest {
        server.enqueue(ok("""{"valid":true,"claims":{"subjectId":"eu_1","projectId":"proj_1","expiresAt":"2099-01-01T00:00:00Z"}}"""))
        val result = gate.verifySession("end.user.jwt", "partner.jwt")
        assertTrue(result.valid)
        assertEquals("eu_1", result.claims?.subjectId)
        assertEquals("proj_1", result.claims?.projectId)
        assertNull(result.reason)
        val r = next()
        assertEquals("/@test-project/gate/verify-session", r.path)
        assertEquals("Bearer partner.jwt", r.getHeader("Authorization"))
        assertEquals(mapOf("token" to "end.user.jwt"), body(r))
    }

    @Test
    fun `verifySession returns valid false with the reason for a refused token`() = runTest {
        server.enqueue(ok("""{"valid":false,"reason":"token_expired"}"""))
        val result = gate.verifySession("old.jwt", "partner.jwt")
        assertFalse(result.valid)
        assertNull(result.claims)
        assertEquals("token_expired", result.reason)
    }
}
