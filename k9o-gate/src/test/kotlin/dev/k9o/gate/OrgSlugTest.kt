package dev.k9o.gate

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

class OrgSlugTest {
    private fun r(base: String?, slug: String? = null) = resolveOrgBaseUrl(base, slug)

    @Test
    fun `full org url is unchanged with or without a slug`() {
        assertEquals("https://acme.api.k9o.io", r("https://acme.api.k9o.io"))
        assertEquals("https://acme.api.k9o.io", r("https://acme.api.k9o.io/", "other"))
        assertEquals("https://acme.api-de01.k9o.io", r("https://acme.api-de01.k9o.io", "other"))
    }

    @Test
    fun `custom domain localhost and ip are unchanged with or without a slug`() {
        for (b in listOf("https://auth.example.com", "http://localhost:3000", "http://127.0.0.1:8080", "https://gateway.corp.internal")) {
            assertEquals(b, r(b))
            assertEquals(b, r(b, "acme"))
            assertEquals(b, r("$b/", "acme"))
        }
    }

    @Test
    fun `platform host plus slug composes the org host and keeps the port`() {
        assertEquals("https://acme.api.k9o.io", r("https://api.k9o.io", "acme"))
        assertEquals("https://acme.api-staging.k9o.io", r("https://api-staging.k9o.io", "acme"))
        assertEquals("https://acme.api.corp.internal:8443", r("https://api.corp.internal:8443/", "acme"))
    }

    @Test
    fun `platform host without a slug is unchanged and a regional platform host ignores the slug`() {
        assertEquals("https://api.k9o.io", r("https://api.k9o.io"))
        assertEquals("https://api-de01.k9o.io", r("https://api-de01.k9o.io", "acme"))
    }

    @Test
    fun `slug only uses the public cloud org host`() {
        assertEquals("https://acme.api.k9o.io", r(null, "acme"))
        assertEquals("https://a1-b2.api.k9o.io", r("", "a1-b2"))
        assertEquals("https://${"a".repeat(63)}.api.k9o.io", r(null, "a".repeat(63)))
    }

    @Test
    fun `bad slugs are refused`() {
        for (s in listOf("Acme", "acme-", "a", "a".repeat(64), "1acme")) {
            val expected = mapOf("field" to "orgSlug", "slug" to s)
            val e1 = assertThrows(K9OGateConfigException::class.java) { r(null, s) }
            assertEquals(s, "K9O_INVALID_FIELD_VALUE", e1.code)
            assertEquals(s, expected, e1.context)
            val e2 = assertThrows(K9OGateConfigException::class.java) { r("https://api.k9o.io", s) }
            assertEquals(s, "K9O_INVALID_FIELD_VALUE", e2.code)
            assertEquals(s, expected, e2.context)
        }
    }

    @Test
    fun `a bad slug is not checked when the url is used as given`() {
        assertEquals("https://auth.example.com", r("https://auth.example.com", "Bad Slug"))
    }

    @Test
    fun `neither url nor slug is an invalid base url error never the apex`() {
        for (args in listOf<Pair<String?, String?>>(null to null, "" to null, null to "", "  " to null)) {
            val e = assertThrows(K9OGateConfigException::class.java) { r(args.first, args.second) }
            assertEquals("K9O_INVALID_BASE_URL", e.code)
            assertEquals(mapOf("field" to "baseUrl"), e.context)
        }
        val e = assertThrows(K9OGateConfigException::class.java) { GateClient(projectSlug = "p") }
        assertEquals("K9O_INVALID_BASE_URL", e.code)
        assertEquals(mapOf("field" to "baseUrl"), e.context)
        // Still an IllegalArgumentException for callers that catch the older type.
        assertTrue(e is IllegalArgumentException)
    }

    private fun recorder(urls: MutableList<String>): OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req: Request = chain.request()
        urls += req.url.toString()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(authResultJson().toResponseBody("application/json".toMediaType())).build()
    }).build()

    @Test
    fun `a request goes to the composed host`() = runTest {
        // GateAuthClient with no probe, as RegionTest does: no network is touched.
        suspend fun sentTo(base: String?, slug: String?): String {
            val urls = mutableListOf<String>()
            val c = GateAuthClient(resolveOrgBaseUrl(base, slug), "p", recorder(urls), null)
            val result: AuthResult = c.post("signin", SignInBody("a@b.c", "pw"))
            assertNotNull(result)
            return urls.single()
        }
        assertEquals("https://acme.api.k9o.io/@p/gate/signin", sentTo(null, "acme"))
        assertEquals("https://acme.api.k9o.io/@p/gate/signin", sentTo("https://api.k9o.io", "acme"))
        assertEquals("https://auth.example.com/@p/gate/signin", sentTo("https://auth.example.com", "acme"))
    }
}
