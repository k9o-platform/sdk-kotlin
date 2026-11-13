package dev.k9o.gate

import com.google.gson.Gson
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

private const val BASE = "https://acme.api-de01.k9o.io"

/**
 * The locate door: password sign-in asks the project which region holds the address, and sends the credential only to
 * an origin that passes the rule in `Locate.kt`.
 */
class LocateTest {

    // MARK: - The rule

    private fun pick(region: Any?, origin: Any?, base: String = BASE) = chooseLocatedOrigin(base, region, origin)

    @Test
    fun `uses a sibling region of the same org, family and apex`() =
        assertEquals("https://acme.api-us01.k9o.io", pick("us01", "https://acme.api-us01.k9o.io"))

    @Test
    fun `uses the base's own region`() = assertEquals(BASE, pick("de01", BASE))

    @Test
    fun `accepts a trailing slash`() = assertEquals("https://acme.api-us01.k9o.io", pick("us01", "https://acme.api-us01.k9o.io/"))

    @Test
    fun `falls back to the base for every origin that fails the rule`() {
        val refused = linkedMapOf<String, Pair<Any?, Any?>>(
            "null origin (single host, self-hosted)" to ("de01" to null),
            "a missing answer" to (null to null),
            "http" to ("us01" to "http://acme.api-us01.k9o.io"),
            "userinfo" to ("us01" to "https://u:p@acme.api-us01.k9o.io"),
            "a port" to ("us01" to "https://acme.api-us01.k9o.io:8443"),
            "a path" to ("us01" to "https://acme.api-us01.k9o.io/x"),
            "a query" to ("us01" to "https://acme.api-us01.k9o.io/?a=1"),
            "a fragment" to ("us01" to "https://acme.api-us01.k9o.io/#x"),
            "another org" to ("us01" to "https://evil.api-us01.k9o.io"),
            "another apex" to ("us01" to "https://acme.api-us01.evil.io"),
            "another family" to ("us01" to "https://acme.api-staging-us01.k9o.io"),
            "a code that differs from region" to ("us01" to "https://acme.api-fr01.k9o.io"),
            "a region that is not a code" to ("usa" to "https://acme.api-usa.k9o.io"),
            "a host with no region" to ("us01" to "https://acme.api.k9o.io"),
            "a region as a subdomain" to ("us01" to "https://us01.acme.api-de01.k9o.io"),
            "a look-alike apex" to ("us01" to "https://acme.api-us01.k9o.io.evil.io"),
            "an origin that is not a string" to ("us01" to 5),
            "an origin that is not a URL" to ("us01" to "not a url"),
        )
        for ((name, a) in refused) assertEquals(name, BASE, pick(a.first, a.second))
    }

    @Test
    fun `allows http only when the base is http on loopback, and then only the same host`() {
        assertEquals("http://localhost:8080", pick("de01", "http://localhost:8080", "http://localhost:8080"))
        assertEquals("http://localhost:8080", pick("de01", "http://evil.localhost:8080", "http://localhost:8080"))
        assertEquals("https://localhost", pick("de01", "http://localhost", "https://localhost"))
        assertEquals("http://localhost", pick("de01", "http://localhost", "http://localhost"))
    }

    @Test
    fun `uses the base when the base is not a gateway host shape`() =
        assertEquals("https://gw.internal.example", pick("us01", "https://gw-us01.internal.example", "https://gw.internal.example"))

    // MARK: - The flow

    private class Seen(val method: String, val url: String, val body: String, val authorization: String?)

    private class Harness(base: String = BASE, answer: (String) -> Pair<Int, String>) {
        val seen = mutableListOf<Seen>()
        private val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req: Request = chain.request()
            val buf = okio.Buffer()
            req.body?.writeTo(buf)
            seen += Seen(req.method, req.url.toString(), buf.readUtf8(), req.header("Authorization"))
            val (code, body) = answer(req.url.toString())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
        val client = GateClient(orgBaseUrl = base, projectSlug = "portal", tokenStore = InMemoryTokenStore(), httpClient = http)
    }

    private fun located(region: String, origin: String?) =
        200 to Gson().toJson(mapOf("data" to mapOf("region" to region, "origin" to origin), "meta" to emptyMap<String, Any>()))

    private fun err(status: Int, code: String) = status to errorJson(code)

    private val signedIn = 200 to authResultJson()

    @Test
    fun `sends only the email to the door, then the password to the located origin`() = runTest {
        val h = Harness { u -> if (u.endsWith("/gate/locate")) located("us01", "https://acme.api-us01.k9o.io") else signedIn }
        h.client.signIn("a@b.co", "pw")
        assertEquals(listOf("$BASE/@portal/gate/locate", "https://acme.api-us01.k9o.io/@portal/gate/signin"), h.seen.map { it.url })
        assertEquals("""{"email":"a@b.co"}""", h.seen[0].body)
        assertNull(h.seen[0].authorization)
    }

    @Test
    fun `never sends the password to an origin that fails the rule`() = runTest {
        val h = Harness { u -> if (u.endsWith("/gate/locate")) located("us01", "https://evil.example") else signedIn }
        h.client.signIn("a@b.co", "pw")
        assertEquals("$BASE/@portal/gate/signin", h.seen[1].url)
        assertFalse(h.seen.any { it.url.contains("evil.example") })
    }

    @Test
    fun `uses the base when the door answers origin null`() = runTest {
        val h = Harness { u -> if (u.endsWith("/gate/locate")) located("de01", null) else signedIn }
        h.client.signIn("a@b.co", "pw")
        assertEquals("$BASE/@portal/gate/signin", h.seen[1].url)
    }

    @Test
    fun `uses the base when the door answers 2xx with a malformed body`() = runTest {
        for (bad in listOf("not-json", "{}", "null")) {
            val h = Harness { u -> if (u.endsWith("/gate/locate")) 200 to bad else signedIn }
            h.client.signIn("a@b.co", "pw")
            assertEquals(listOf("$BASE/@portal/gate/locate", "$BASE/@portal/gate/signin"), h.seen.map { it.url })
        }
    }

    @Test
    fun `sends nothing to sign-in when the door is down (503) or rate-limited (429), and raises the door's error`() = runTest {
        val h503 = Harness { err(503, "K9O_GATE_REGION_UNAVAILABLE") }
        try {
            h503.client.signIn("a@b.co", "pw")
            fail("Expected GateError.RegionUnavailable")
        } catch (e: GateError.RegionUnavailable) {
            assertEquals(503, e.statusCode)
        }
        assertEquals(1, h503.seen.size)
        val h429 = Harness { err(429, "K9O_RATE_LIMITED") }
        try {
            h429.client.signIn("a@b.co", "pw")
            fail("Expected GateError.RateLimited")
        } catch (e: GateError.RateLimited) {
            assertEquals(429, e.statusCode)
        }
        assertEquals(1, h429.seen.size)
    }

    @Test
    fun `uses the base when the door answers 404, and the call succeeds`() = runTest {
        val h = Harness { u -> if (u.endsWith("/gate/locate")) err(404, "K9O_NOT_FOUND") else signedIn }
        h.client.signIn("a@b.co", "pw")
        assertEquals(listOf("$BASE/@portal/gate/locate", "$BASE/@portal/gate/signin"), h.seen.map { it.url })
    }

    @Test
    fun `raises when the door answers 404 and then sign-in itself answers 404`() = runTest {
        val h = Harness { err(404, "K9O_NOT_FOUND") }
        try {
            h.client.signIn("a@b.co", "pw")
            fail("Expected GateError.Unknown")
        } catch (e: GateError.Unknown) {
            assertEquals(404, e.statusCode)
        }
        assertEquals(listOf("$BASE/@portal/gate/locate", "$BASE/@portal/gate/signin"), h.seen.map { it.url })
    }

    @Test
    fun `raises any other door failure as the usual error, with no retry`() = runTest {
        val h = Harness { err(500, "K9O_INTERNAL_ERROR") }
        try {
            h.client.signIn("a@b.co", "pw")
            fail("Expected GateError.Unknown")
        } catch (e: GateError.Unknown) {
            assertEquals(500, e.statusCode)
        }
        assertEquals(1, h.seen.size)
    }

    @Test
    fun `does not cache the located origin - the next sign-in asks again`() = runTest {
        var n = 0
        val h = Harness { u ->
            if (!u.endsWith("/gate/locate")) signedIn
            else {
                val region = if (n++ == 0) "us01" else "fr01"
                located(region, "https://acme.api-$region.k9o.io")
            }
        }
        h.client.signIn("a@b.co", "pw")
        h.client.signIn("a@b.co", "pw")
        assertEquals(2, h.seen.count { it.url.endsWith("/gate/locate") })
        assertEquals(
            listOf("https://acme.api-us01.k9o.io/@portal/gate/signin", "https://acme.api-fr01.k9o.io/@portal/gate/signin"),
            h.seen.map { it.url }.filter { it.endsWith("/signin") },
        )
    }

    @Test
    fun `sends refresh and sign-out to the origin that issued the session`() = runTest {
        val h = Harness { u ->
            when {
                u.endsWith("/gate/locate") -> located("us01", "https://acme.api-us01.k9o.io")
                u.endsWith("/signin") -> signedIn
                u.endsWith("/refresh") -> 200 to refreshJson()
                else -> 200 to """{"data":{"revoked":true},"meta":{}}"""
            }
        }
        h.client.signIn("a@b.co", "pw")
        h.client.refreshToken("rt_MockRefreshToken0001")
        // The refresh answered the next token; the session's origin follows it.
        h.client.signOut("rt_NextToken0002")
        val urls = h.seen.map { it.url }
        assertTrue(urls.contains("https://acme.api-us01.k9o.io/@portal/gate/refresh"))
        assertTrue(urls.contains("https://acme.api-us01.k9o.io/@portal/gate/signout"))
        h.client.refreshToken("rt_NextToken0002")
        assertEquals("$BASE/@portal/gate/refresh", h.seen.last().url)
    }

    @Test
    fun `keeps at most 64 session hints, dropping the oldest`() = runTest {
        var n = 0
        val h = Harness { u ->
            when {
                u.endsWith("/gate/locate") -> located("us01", "https://acme.api-us01.k9o.io")
                u.endsWith("/signin") -> 200 to authResultJson().replace("rt_MockRefreshToken0001", "cap-${n++}")
                else -> 200 to refreshJson()
            }
        }
        repeat(65) { h.client.signIn("a@b.co", "pw") }
        h.client.refreshToken("cap-0")
        assertEquals("$BASE/@portal/gate/refresh", h.seen.last().url)
        h.client.refreshToken("cap-64")
        assertEquals("https://acme.api-us01.k9o.io/@portal/gate/refresh", h.seen.last().url)
    }
}
