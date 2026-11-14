package dev.k9o.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RecommendationTest {
    private fun fail(status: Int, rec: String): KError {
        val body = """{"code":"K9O_X","message":"msg","context":{"x":1},"recommendation":$rec}"""
        val core = K9OCore("https://acme.api.k9o.io/", "member-portal", accessToken = "t", transport = StubTransport(status, body))
        return runBlocking { assertFailsWith<KError> { core.ai.embed("a") } }
    }

    private fun check(status: Int, rec: String, text: String?, wait: Double?) {
        val e = fail(status, rec)
        assertEquals("K9O_X", e.code)
        assertEquals("msg", e.message)
        assertEquals(status, e.status)
        assertEquals(mapOf("x" to 1.0), e.context)
        assertEquals(text, e.recommendation)
        assertEquals(wait, e.retryAfterSec)
    }

    @Test fun `object with action`() = check(403, """{"action":"Open Billing"}""", "Open Billing", null)
    @Test fun `object with retryAfterSec and note`() = check(429, """{"retryAfterSec":30,"note":"retry"}""", "retry", 30.0)
    @Test fun `null`() = check(429, "null", null, null)
    @Test fun `string`() = check(400, "\"do this\"", "do this", null)
    @Test fun `number`() = check(400, "5", null, null)
    @Test fun `array`() = check(400, """["a","b"]""", null, null)
    @Test fun `negative retryAfterSec ignored`() = check(429, """{"retryAfterSec":-1}""", null, null)

    private class HeaderTransport(private val body: String, private val headers: Map<String, String>) : HttpTransport {
        override suspend fun send(request: HttpRequest) = HttpResponse(404, body, headers)
        override suspend fun stream(request: HttpRequest) =
            HttpStreamResponse(404, java.io.StringReader(body), headers)
    }

    private fun notFound(body: String, headers: Map<String, String>): KError {
        val core = K9OCore("https://acme.api.k9o.io/", "member-portal", accessToken = "t", transport = HeaderTransport(body, headers))
        return runBlocking { assertFailsWith<KError> { core.ai.embed("a") } }
    }

    private val plain = """{"code":"K9O_NOT_FOUND","message":"msg"}"""

    @Test fun `request id comes from the header when the body has none`() =
        assertEquals("req_abc", notFound(plain, mapOf("x-k9o-request-id" to "req_abc")).requestId)

    @Test fun `a body request id wins over the header`() = assertEquals(
        "req_body",
        notFound("""{"code":"K9O_NOT_FOUND","message":"msg","requestId":"req_body"}""", mapOf("x-k9o-request-id" to "req_hdr")).requestId,
    )

    @Test fun `no header and no body field leaves request id null`() = assertEquals(null, notFound(plain, emptyMap()).requestId)

    @Test fun `the header name is matched in any case`() =
        assertEquals("req_abc", notFound(plain, mapOf("X-K9O-Request-Id" to "req_abc")).requestId)

    @Test fun `a non-KError body still carries the header id`() =
        assertEquals("req_abc", notFound("<html>", mapOf("x-k9o-request-id" to "req_abc")).requestId)

    @Test fun `opening an event stream carries the header id`() {
        val core = K9OCore("https://acme.api.k9o.io/", "member-portal", accessToken = "t",
            transport = HeaderTransport(plain, mapOf("x-k9o-request-id" to "req_abc")))
        val e = runBlocking { assertFailsWith<KError> { core.streams.subscribe("orders").collect { } } }
        assertEquals("req_abc", e.requestId)
    }
}
