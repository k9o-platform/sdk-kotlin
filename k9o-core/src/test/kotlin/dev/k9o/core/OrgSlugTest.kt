package dev.k9o.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val GEN_OK =
    """{"data":{"modelId":"m","content":"c","finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1,"totalTokens":2},"usageType":null},"meta":{}}"""

private class SlugTransport : HttpTransport {
    val probes = mutableListOf<String>()
    val urls = mutableListOf<String>()
    override suspend fun probe(url: String): String? { probes += url; return null }
    override suspend fun send(request: HttpRequest): HttpResponse { urls += request.url; return HttpResponse(200, GEN_OK) }
}

class OrgSlugTest {
    private fun r(base: String?, slug: String? = null) = resolveBaseUrl(base, slug)

    @Test
    fun `full org url is unchanged with or without a slug`() {
        assertEquals("https://acme.api.k9o.io", r("https://acme.api.k9o.io"))
        assertEquals("https://acme.api.k9o.io", r("https://acme.api.k9o.io/", "other"))
        assertEquals("https://acme.api-de01.k9o.io", r("https://acme.api-de01.k9o.io", "other"))
    }

    @Test
    fun `custom domain localhost and ip are unchanged with or without a slug`() {
        for (b in listOf("https://auth.example.com", "http://localhost:3000", "http://127.0.0.1:8080", "https://gateway.corp.internal")) {
            assertEquals(b, r(b), b)
            assertEquals(b, r(b, "acme"), b)
            assertEquals(b, r("$b/", "acme"), b)
        }
    }

    @Test
    fun `platform host plus slug composes the org host and keeps the port`() {
        assertEquals("https://acme.api.k9o.io", r("https://api.k9o.io", "acme"))
        assertEquals("https://acme.api-staging.k9o.io", r("https://api-staging.k9o.io", "acme"))
        assertEquals("https://acme.api.corp.internal:8443", r("https://api.corp.internal:8443/", "acme"))
        assertEquals("http://acme.api.k9o.io", r("http://api.k9o.io", "acme"))
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
    }

    @Test
    fun `bad slugs are refused`() {
        for (s in listOf("Acme", "acme-", "a", "a".repeat(64), "1acme", "ac me")) {
            val e1 = assertFailsWith<KError>(s) { r(null, s) }
            assertEquals("K9O_INVALID_FIELD_VALUE", e1.code, s)
            assertEquals(mapOf("field" to "orgSlug", "slug" to s), e1.context, s)
            val e2 = assertFailsWith<KError>(s) { r("https://api.k9o.io", s) }
            assertEquals("K9O_INVALID_FIELD_VALUE", e2.code, s)
            assertEquals(mapOf("field" to "orgSlug", "slug" to s), e2.context, s)
        }
        assertEquals("https://${"a".repeat(63)}.api.k9o.io", r(null, "a".repeat(63)))
    }

    @Test
    fun `a bad slug is not checked when the url is used as given`() {
        assertEquals("https://auth.example.com", r("https://auth.example.com", "Bad Slug"))
    }

    @Test
    fun `neither base url nor slug is an invalid base url error never the apex`() {
        for (args in listOf<Pair<String?, String?>>(null to null, "" to null, null to "", "  " to null)) {
            val e = assertFailsWith<KError> { r(args.first, args.second) }
            assertEquals("K9O_INVALID_BASE_URL", e.code)
            assertEquals(mapOf("field" to "baseUrl"), e.context)
        }
        val e = assertFailsWith<KError> { K9OCore() }
        assertEquals("K9O_INVALID_BASE_URL", e.code)
        assertEquals(mapOf("field" to "baseUrl"), e.context)
    }

    @Test
    fun `a request goes to the composed host`() = runBlocking {
        val t = SlugTransport()
        K9OCore(baseUrl = "https://api.k9o.io", projectSlug = "p", accessToken = "tok", transport = t, orgSlug = "acme").ai.generate("a")
        assertEquals(listOf("https://acme.api.k9o.io/"), t.probes)
        assertEquals(listOf("https://acme.api.k9o.io/@p/ai/generate"), t.urls)

        val t2 = SlugTransport()
        K9OCore(projectSlug = "p", accessToken = "tok", transport = t2, orgSlug = "acme").ai.generate("a")
        assertEquals(listOf("https://acme.api.k9o.io/@p/ai/generate"), t2.urls)

        val t3 = SlugTransport()
        K9OCore("https://auth.example.com", "p", accessToken = "tok", transport = t3, orgSlug = "acme").ai.generate("a")
        assertEquals(listOf("https://auth.example.com/@p/ai/generate"), t3.urls)
        assertEquals(emptyList(), t3.probes)
    }
}
