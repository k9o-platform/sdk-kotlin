package dev.k9o.gate

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

/** The error shape: `code`, `status`, `message`, `recommendation`, `context`, `requestId`, `publicMessage`, and `errors`. */
class GateErrorsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        // Sign-in asks the locate door first; this door answers normally so the next queued response is sign-in's.
        server.dispatcher = object : QueueDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.endsWith("/gate/locate") == true) {
                    MockResponse().setBody("""{"data":{"region":"de01","origin":null},"meta":{}}""")
                } else {
                    super.dispatch(request)
                }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientWith(errors: String? = null): GateClient =
        if (errors == null) {
            GateClient(orgBaseUrl = server.url("").toString(), projectSlug = "test-project", tokenStore = InMemoryTokenStore(), httpClient = OkHttpClient())
        } else {
            GateClient(orgBaseUrl = server.url("").toString(), projectSlug = "test-project", tokenStore = InMemoryTokenStore(), httpClient = OkHttpClient(), errors = errors)
        }

    /** Signs in against the queued response and returns the error it raised. */
    private suspend fun signInError(gate: GateClient): GateError {
        try {
            gate.signIn("u@e.com", "p")
        } catch (e: GateError) {
            return e
        }
        fail("Expected a GateError")
        throw AssertionError("unreachable")
    }

    private val unknownCodeBody =
        """{"code":"K9O_MAGIC_LINK_REDIRECT_NOT_CONFIGURED","message":"x","recommendation":{"action":"Register a default redirect"}}"""

    // (a) An unknown code is kept, with the recommendation, the request id and the public message, in full mode.
    @Test
    fun `unknown code is kept with its recommendation, request id and public message in full mode`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("x-k9o-request-id", "req_abc").setBody(unknownCodeBody))
        val e = signInError(clientWith())
        val unknown = e as? GateError.Unknown ?: throw AssertionError("Expected GateError.Unknown, got $e")
        assertEquals("K9O_MAGIC_LINK_REDIRECT_NOT_CONFIGURED", unknown.code)
        assertEquals(503, unknown.statusCode)
        assertEquals(503, unknown.status)
        assertEquals("Register a default redirect", unknown.recommendation)
        assertEquals("req_abc", unknown.requestId)
        assertEquals("Something went wrong. Try again later.", unknown.publicMessage)
        assertEquals("x", unknown.message)
        assertEquals(unknownCodeBody, unknown.body)
    }

    // (b) The same input, redacted: generic, message is the public sentence, the gateway's text is dropped.
    @Test
    fun `unknown code is reduced in redacted mode and keeps status and request id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("x-k9o-request-id", "req_abc").setBody(unknownCodeBody))
        val e = signInError(clientWith(errors = "redacted"))
        val unknown = e as? GateError.Unknown ?: throw AssertionError("Expected GateError.Unknown, got $e")
        assertNull(unknown.code)
        assertEquals("Something went wrong. Try again later.", unknown.message)
        assertEquals("Something went wrong. Try again later.", unknown.publicMessage)
        assertNull(unknown.recommendation)
        assertTrue(unknown.context.isEmpty())
        assertNull(unknown.body)
        assertEquals("req_abc", unknown.requestId)
        assertEquals(503, unknown.status)
        assertEquals(503, unknown.statusCode)
    }

    // (c) A known code keeps its code, its class and its status in both modes.
    @Test
    fun `known code keeps its code and class in both modes`() = runTest {
        val body = """{"code":"K9O_INVALID_CREDENTIALS","message":"Wrong password","context":{},"recommendation":null}"""
        for (mode in listOf(null, "full", "redacted")) {
            server.enqueue(MockResponse().setResponseCode(401).setHeader("x-k9o-request-id", "req_c").setBody(body))
            val e = signInError(clientWith(mode))
            val invalid = e as? GateError.InvalidCredentials ?: throw AssertionError("Expected InvalidCredentials in mode $mode, got $e")
            assertEquals("K9O_INVALID_CREDENTIALS", invalid.code)
            assertEquals(401, invalid.status)
            assertEquals("req_c", invalid.requestId)
            assertEquals("You are not signed in or not allowed to do this.", invalid.publicMessage)
            if (mode == "redacted") {
                assertEquals("You are not signed in or not allowed to do this.", invalid.message)
                assertNull(invalid.body)
            } else {
                assertEquals("Invalid credentials.", invalid.message)
            }
        }
    }

    // (d) A rate-limited error keeps its wait in redacted mode.
    @Test
    fun `rate limited keeps retryAfterSec in redacted mode`() = runTest {
        val body = """{"code":"K9O_RATE_LIMITED","message":"Slow down","recommendation":{"retryAfterSec":30,"note":"Wait before you retry"}}"""
        server.enqueue(MockResponse().setResponseCode(429).setHeader("x-k9o-request-id", "req_d").setBody(body))
        val e = signInError(clientWith(errors = "redacted"))
        val limited = e as? GateError.RateLimited ?: throw AssertionError("Expected RateLimited, got $e")
        assertEquals("K9O_RATE_LIMITED", limited.code)
        assertEquals(30.0, limited.retryAfterSec!!, 0.0)
        assertEquals("Too many attempts. Try again later.", limited.publicMessage)
        assertEquals("Too many attempts. Try again later.", limited.message)
        assertNull(limited.recommendation)
        assertTrue(limited.context.isEmpty())
        assertEquals("req_d", limited.requestId)
    }

    @Test
    fun `rate limited keeps the note and retryAfterSec in full mode`() = runTest {
        val body = """{"code":"K9O_RATE_LIMITED","message":"Slow down","recommendation":{"retryAfterSec":30,"note":"Wait before you retry"}}"""
        server.enqueue(MockResponse().setResponseCode(429).setBody(body))
        val limited = signInError(clientWith()) as GateError.RateLimited
        assertEquals("Wait before you retry", limited.recommendation)
        assertEquals(30.0, limited.retryAfterSec!!, 0.0)
        assertNull(limited.requestId)
    }

    // (e) An invalid errors value is refused with a typed config error.
    @Test
    fun `invalid errors value is refused`() {
        val e = assertThrows(K9OGateConfigException::class.java) { clientWith(errors = "verbose") }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertEquals(mapOf("field" to "errors", "value" to "verbose"), e.context)
    }

    @Test
    fun `publicMessage is chosen by status class`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"code":"K9O_NOT_FOUND","message":"gone"}"""))
        val notFound = signInError(clientWith()) as GateError.Unknown
        assertEquals("Not found.", notFound.publicMessage)
        server.enqueue(MockResponse().setResponseCode(418).setBody("not json"))
        val other = signInError(clientWith()) as GateError.Unknown
        assertEquals("The request could not be completed.", other.publicMessage)
        assertNull(other.code)
        assertEquals("not json", other.body)
    }
}
