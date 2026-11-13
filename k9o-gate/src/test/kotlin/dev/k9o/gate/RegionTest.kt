package dev.k9o.gate

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An OkHttp client that records each request and answers 200 with a canned sign-in body, without any network. */
private class Recorder {
    val requests = mutableListOf<Request>()
    val bodies = mutableListOf<String>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request()
        requests += req
        val buf = okio.Buffer()
        req.body?.writeTo(buf)
        bodies += buf.readUtf8()
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(authResultJson().toResponseBody("application/json".toMediaType())).build()
    }).build()
}

class RegionTest {
    private class Probe(private val location: String? = null, private val fails: Boolean = false) {
        val urls = mutableListOf<String>()
        val fn: suspend (String) -> String? = { url ->
            urls += url
            if (fails) throw IOException("down")
            location
        }
    }

    private fun auth(base: String, rec: Recorder, probe: Probe?) =
        GateAuthClient(base, "p", rec.client, probe?.fn)

    private suspend fun signIn(c: GateAuthClient): AuthResult = c.post("signin", SignInBody("a@b.c", "pw"))

    @Test
    fun `short staging address probes once then sends sign-in to the regional host`() = runTest {
        val rec = Recorder()
        val probe = Probe("https://acme.api-staging-de01.k9o.io/")
        val c = auth("https://acme.api-staging.k9o.io", rec, probe)
        signIn(c); signIn(c)
        assertEquals(listOf("https://acme.api-staging.k9o.io/"), probe.urls)
        assertEquals(2, rec.requests.size)
        assertTrue(rec.requests.all { it.url.toString() == "https://acme.api-staging-de01.k9o.io/@p/gate/signin" })
        assertTrue(rec.bodies[0].contains("\"password\""))
    }

    @Test
    fun `short production address resolves to the 308 location`() = runTest {
        val rec = Recorder()
        signIn(auth("https://acme.api.k9o.io", rec, Probe("https://acme.api-de01.k9o.io/")))
        assertEquals("https://acme.api-de01.k9o.io/@p/gate/signin", rec.requests.single().url.toString())
    }

    @Test
    fun `regional localhost ip and foreign addresses skip the probe`() = runTest {
        for (b in listOf(
            "https://acme.api-de01.k9o.io", "http://localhost:8080", "http://127.0.0.1:8080",
            "https://gateway.corp.internal", "https://localhost",
        )) {
            val rec = Recorder()
            val probe = Probe("https://evil.example/")
            signIn(auth(b, rec, probe))
            assertTrue(b, probe.urls.isEmpty())
            assertTrue(b, rec.requests.single().url.toString().startsWith(b))
        }
    }

    @Test
    fun `no probe is made when none is configured`() = runTest {
        val rec = Recorder()
        signIn(auth("https://acme.api.k9o.io", rec, null))
        assertEquals("https://acme.api.k9o.io/@p/gate/signin", rec.requests.single().url.toString())
    }

    @Test
    fun `hostile or mismatched location is ignored`() = runTest {
        for (loc in listOf(
            "https://evil.example/", "https://other.api-de01.k9o.io/", "http://acme.api-de01.k9o.io/",
            "https://acme.api-de01.k9o.io:8443/", "https://u@acme.api-de01.k9o.io/", "https://acme.api-de01.evil.io/",
            "https://acme.api-staging-de01.k9o.io/",
        )) {
            val rec = Recorder()
            signIn(auth("https://acme.api.k9o.io", rec, Probe(loc)))
            assertEquals(loc, "https://acme.api.k9o.io/@p/gate/signin", rec.requests.single().url.toString())
        }
    }

    @Test
    fun `probe network failure is not cached`() = runTest {
        val rec = Recorder()
        val probe = Probe(fails = true)
        val c = auth("https://acme.api.k9o.io", rec, probe)
        signIn(c); signIn(c)
        assertEquals(2, probe.urls.size)
        assertEquals("https://acme.api.k9o.io/@p/gate/signin", rec.requests.first().url.toString())
    }

    @Test
    fun `an answer that is not a redirect is cached`() = runTest {
        val rec = Recorder()
        val probe = Probe(null)
        val c = auth("https://acme.api.k9o.io", rec, probe)
        signIn(c); signIn(c)
        assertEquals(1, probe.urls.size)
    }

    @Test
    fun `default probe sends a credential-free HEAD without following the redirect`() = runTest {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setResponseCode(308).setHeader("Location", "https://acme.api-de01.k9o.io/"))
        srv.start()
        try {
            val withAuth = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer secret").header("X-Default", "1").build())
            }).build()
            val loc = defaultProbe(withAuth)(srv.url("/").toString())
            val req = srv.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("HEAD", req.method)
            assertNull(req.getHeader("Authorization"))
            assertNull(req.getHeader("X-Default"))
            assertEquals("https://acme.api-de01.k9o.io/", loc)
            assertEquals(1, srv.requestCount)
        } finally {
            srv.shutdown()
        }
    }

    @Test
    fun `default probe returns null for a non-308 answer`() = runTest {
        val srv = MockWebServer()
        srv.enqueue(MockResponse().setResponseCode(404))
        srv.start()
        try {
            assertNull(defaultProbe(OkHttpClient())(srv.url("/").toString()))
        } finally {
            srv.shutdown()
        }
    }
}
