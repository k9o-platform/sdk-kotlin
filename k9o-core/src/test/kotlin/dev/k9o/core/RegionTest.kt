package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OK_GEN =
    """{"data":{"modelId":"m","content":"c","finishReason":"stop","usage":{"promptTokens":1,"completionTokens":1,"totalTokens":2},"usageType":"chat"},"meta":{}}"""

private class RegionTransport(
    private val location: String? = null,
    private val probeFails: Boolean = false,
    private val responses: MutableList<HttpResponse> = mutableListOf(HttpResponse(200, OK_GEN)),
) : HttpTransport {
    val probes = mutableListOf<String>()
    val requests = mutableListOf<HttpRequest>()
    override suspend fun probe(url: String): String? {
        probes += url
        if (probeFails) throw java.io.IOException("down")
        return location
    }
    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return if (responses.size > 1) responses.removeAt(0) else responses.first()
    }
}

class RegionTest {
    private fun core(base: String, t: HttpTransport) = K9OCore(base, "p", accessToken = "tok", transport = t)

    @Test
    fun `short address probes once without credentials then sends everything to the regional host`() = runBlocking {
        val t = RegionTransport(location = "https://acme.api-staging-de01.k9o.io/")
        val c = core("https://acme.api-staging.k9o.io", t)
        c.ai.generate("a"); c.ai.generate("b")
        assertEquals(listOf("https://acme.api-staging.k9o.io/"), t.probes)
        assertEquals(2, t.requests.size)
        assertTrue(t.requests.all { it.url == "https://acme.api-staging-de01.k9o.io/@p/ai/generate" })
    }

    @Test
    fun `short production address resolves to the 308 location`() = runBlocking {
        val t = RegionTransport(location = "https://acme.api-de01.k9o.io/")
        core("https://acme.api.k9o.io", t).ai.generate("a")
        assertEquals("https://acme.api-de01.k9o.io/@p/ai/generate", t.requests.single().url)
    }

    @Test
    fun `regional localhost and foreign addresses skip the probe`() = runBlocking {
        for (b in listOf("https://acme.api-de01.k9o.io", "http://localhost:8080", "https://gateway.corp.internal")) {
            val t = RegionTransport(location = "https://evil.example/")
            core(b, t).ai.generate("a")
            assertTrue(t.probes.isEmpty(), b)
            assertTrue(t.requests.single().url.startsWith(b), b)
        }
    }

    @Test
    fun `hostile or mismatched location is ignored`() = runBlocking {
        for (loc in listOf("https://evil.example/", "https://other.api-de01.k9o.io/", "http://acme.api-de01.k9o.io/", "https://acme.api-de01.k9o.io:8443/", "https://u@acme.api-de01.k9o.io/", "https://acme.api-de01.evil.io/")) {
            val t = RegionTransport(location = loc)
            core("https://acme.api.k9o.io", t).ai.generate("a")
            assertEquals("https://acme.api.k9o.io/@p/ai/generate", t.requests.single().url, loc)
        }
    }

    @Test
    fun `probe network failure is not cached`() = runBlocking {
        val t = RegionTransport(probeFails = true)
        val c = core("https://acme.api.k9o.io", t)
        c.ai.generate("a"); c.ai.generate("b")
        assertEquals(2, t.probes.size)
        assertEquals("https://acme.api.k9o.io/@p/ai/generate", t.requests.first().url)
    }

    @Test
    fun `a 400 naming the regional host is retried once there and remembered`() = runBlocking {
        val refusal = HttpResponse(
            400,
            """{"code":"K9O_INVALID_REQUEST_SHAPE","message":"m","context":{"reason":"region_free_host_takes_no_credentials","regionalHost":"acme.api-de01.k9o.io"}}""",
        )
        val t = RegionTransport(responses = mutableListOf(refusal, HttpResponse(200, OK_GEN)))
        val c = core("https://acme.api.k9o.io", t)
        c.ai.generate("a"); c.ai.generate("b")
        assertEquals(
            listOf("https://acme.api.k9o.io/@p/ai/generate", "https://acme.api-de01.k9o.io/@p/ai/generate", "https://acme.api-de01.k9o.io/@p/ai/generate"),
            t.requests.map { it.url },
        )
    }

    @Test
    fun `a 400 naming a foreign host is not followed`() = runBlocking {
        val refusal = HttpResponse(400, """{"code":"K9O_INVALID_REQUEST_SHAPE","message":"m","context":{"regionalHost":"evil.example"}}""")
        val t = RegionTransport(responses = mutableListOf(refusal))
        val e = runCatching { core("https://acme.api.k9o.io", t).ai.generate("a") }.exceptionOrNull() as KError
        assertEquals("K9O_INVALID_REQUEST_SHAPE", e.code)
        assertEquals(1, t.requests.size)
    }

    @Test
    fun `usageType is on the wire only when set`() = runBlocking {
        val t = RegionTransport()
        val c = core("http://localhost:1", t)
        c.ai.generate("a", usageType = "batch")
        c.ai.generate("b")
        assertEquals("batch", JsonParser.parseString(t.requests[0].body).asJsonObject.get("usageType").asString)
        assertNull(JsonParser.parseString(t.requests[1].body).asJsonObject.get("usageType"))
    }

    @Test
    fun `default read timeout is 60 s and configurable`() {
        assertEquals(60_000L, OkHttpTransport.DEFAULT_READ_TIMEOUT_MILLIS)
        val f = OkHttpTransport::class.java.getDeclaredField("client").also { it.isAccessible = true }
        assertEquals(60_000, (f.get(OkHttpTransport()) as OkHttpClient).readTimeoutMillis)
        assertEquals(5_000, (f.get(OkHttpTransport(5_000)) as OkHttpClient).readTimeoutMillis)
    }

    @Test
    fun `okhttp probe returns the 308 location and does not follow it`() = runBlocking {
        val srv = okhttp3.mockwebserver.MockWebServer()
        srv.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(308).setHeader("Location", "https://acme.api-de01.k9o.io/"))
        srv.start()
        val loc = OkHttpTransport().probe(srv.url("/").toString())
        val req = srv.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("HEAD", req.method)
        assertNull(req.getHeader("authorization"))
        assertEquals("https://acme.api-de01.k9o.io/", loc)
        srv.shutdown()
    }
}
