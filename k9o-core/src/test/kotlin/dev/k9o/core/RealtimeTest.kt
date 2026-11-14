package dev.k9o.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Serves one scripted SSE body per connection, then repeats the last. */
private class FeedTransport(private val bodies: List<String>, private val status: Int = 200, private val errorBody: String = "") :
    HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    override suspend fun send(request: HttpRequest): HttpResponse = HttpResponse(status, errorBody)
    override suspend fun stream(request: HttpRequest): HttpStreamResponse {
        requests += request
        if (status !in 200..299) return HttpStreamResponse(status, StringReader(errorBody))
        return HttpStreamResponse(status, StringReader(bodies[minOf(requests.size - 1, bodies.size - 1)]))
    }
}

private fun ev(id: String, type: String = "row_inserted", payload: String = "{\"n\":1}", cursor: String? = id) =
    (if (cursor == null) "" else "id: $cursor\n") +
        "data: {\"topic\":\"collections.posts.*\",\"eventType\":\"$type\",\"payload\":$payload,\"occurredAt\":\"2026-01-01T00:00:00Z\",\"eventId\":\"$id\"}\n\n"

class RealtimeTest {
    private fun core(t: HttpTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io", slug, accessToken = "tok", transport = t)

    private val root = "https://acme.api.k9o.io/@member-portal/realtime"

    @Test fun `replay sends only the given fields and parses the page`() = runBlocking {
        val t = StubTransport(
            body = """{"data":{"topic":"audit.project.*","events":[{"topic":"audit.project.*","eventType":"audit.x","payload":{"a":1},"occurredAt":"2026-01-01T00:00:00Z","sequence":"5","eventId":"evt_abc"},{"eventType":"audit.y","payload":null,"occurredAt":"2026-01-01T00:00:01Z","sequence":6},{"nope":1}],"nextCursor":"6","hasMore":true},"meta":{"request_id":"r1"}}""",
        )
        val r = core(t).realtime.replay("audit.project.*", fromSequence = "4", untilSequence = "9", limit = 2, afterAuditEventId = "evt_a1")
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$root/replay", req.url)
        assertEquals(
            JsonParser.parseString("""{"topic":"audit.project.*","fromSequence":"4","untilSequence":"9","limit":2,"afterAuditEventId":"evt_a1"}"""),
            JsonParser.parseString(req.body),
        )
        assertEquals(2, r.data.events.size)
        assertEquals("5", r.data.events[0].sequence)
        assertEquals("evt_abc", r.data.events[0].eventId)
        assertEquals(1, r.data.events[0].payload.asJsonObject.get("a").asInt)
        assertEquals("6", r.data.events[1].sequence)
        assertEquals("audit.project.*", r.data.events[1].topic)
        assertNull(r.data.events[1].eventId)
        assertEquals("6", r.data.nextCursor)
        assertTrue(r.data.hasMore)
        assertNull(r.data.resync)
        core(t).realtime.replay("audit.project.*")
        assertEquals(JsonParser.parseString("""{"topic":"audit.project.*"}"""), JsonParser.parseString(t.requests[1].body))
    }

    @Test fun `replay of a topic with no ledger answers resync`() = runBlocking {
        val t = StubTransport(body = """{"data":{"topic":"collections.posts.*","events":[],"nextCursor":null,"hasMore":false,"resync":"reread-source"},"meta":{}}""")
        val r = core(t).realtime.replay("collections.posts.*")
        assertTrue(r.data.events.isEmpty())
        assertNull(r.data.nextCursor)
        assertEquals(false, r.data.hasMore)
        assertEquals("reread-source", r.data.resync)
    }

    @Test fun `replay validates before any request`() = runBlocking {
        val t = StubTransport(body = """{"data":{"topic":"t","events":[],"nextCursor":null,"hasMore":false},"meta":{}}""")
        val rt = core(t).realtime
        fun code(block: suspend () -> Unit) = assertFailsWith<KError> { runBlocking { block() } }.code
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("a".repeat(201)) })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", fromSequence = "-1") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", fromSequence = "1.5") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", untilSequence = "x") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", limit = 0) })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", limit = 10_001) })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", afterAuditEventId = "abc") })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", afterAuditEventId = "evt_" + "a".repeat(41)) })
        assertEquals("K9O_INVALID_FIELD_VALUE", code { rt.replay("t", afterAuditEventId = "evt_") })
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", code { core(t, null).realtime.replay("t") })
        assertTrue(t.requests.isEmpty())
        rt.replay("a".repeat(200), fromSequence = "0", limit = 10_000, afterAuditEventId = "evt_" + "a".repeat(40))
        assertEquals(1, t.requests.size)
    }

    @Test fun `replay maps gateway errors to KError`() = runBlocking {
        val t = StubTransport(403, """{"code":"K9O_RBAC_DENY","message":"no audit access","context":{},"recommendation":null}""")
        val e = assertFailsWith<KError> { core(t).realtime.replay("audit.project.*") }
        assertEquals("K9O_RBAC_DENY", e.code)
        assertEquals(403, e.status)
    }

    @Test fun `subscribe sends topic and filter and reads events`() = runBlocking {
        val t = FeedTransport(listOf(": hello\n\n" + ev("evt_1") + ev("evt_2", "row_updated", "\"two\"")))
        val filter = JsonObject().apply { addProperty("status", "open") }
        val events = withTimeout(10_000) { core(t).realtime.subscribe("collections.posts.*", filter).take(2).toList() }
        assertEquals(listOf("evt_1", "evt_2"), events.map { it.eventId })
        assertEquals("row_updated", events[1].eventType)
        assertEquals("two", events[1].payload.asString)
        assertEquals(1, events[0].payload.asJsonObject.get("n").asInt)
        assertEquals("2026-01-01T00:00:00Z", events[0].occurredAt)
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals("$root/subscribe?topic=collections.posts.*&filter=%7B%22status%22%3A%22open%22%7D", req.url)
        assertEquals("text/event-stream", req.headers["accept"])
        assertEquals("Bearer tok", req.headers["authorization"])
        assertNull(req.headers["last-event-id"])
    }

    @Test fun `subscribe omits filter when none is given`() = runBlocking {
        val t = FeedTransport(listOf(ev("evt_1")))
        withTimeout(10_000) { core(t).realtime.subscribe("studio.workflows.runs.*").take(1).toList() }
        assertTrue(!t.requests[0].url.contains("filter="))
    }

    @Test fun `subscribe reconnects with Last-Event-ID and drops re-sent events`() = runBlocking {
        val t = FeedTransport(
            listOf(
                "retry: 1\n\n" + ev("evt_1", cursor = "c1") + ev("evt_2", cursor = "c2"),
                ev("evt_2", cursor = "c2") + ev("evt_3", cursor = "c3"),
            ),
        )
        val events = withTimeout(10_000) { core(t).realtime.subscribe("collections.posts.*").take(3).toList() }
        assertEquals(listOf("evt_1", "evt_2", "evt_3"), events.map { it.eventId })
        assertNull(t.requests[0].headers["last-event-id"])
        assertEquals("c2", t.requests[1].headers["last-event-id"])
    }

    @Test fun `subscribe uses the frame id when the data has no eventId, and ignores malformed frames`() = runBlocking {
        val t = FeedTransport(
            listOf(
                "data: not json\n\ndata: {\"x\":1}\n\ndata: [1]\n\n" +
                    "id: evt_7\ndata: {\"eventType\":\"row_deleted\",\"payload\":{},\"occurredAt\":\"t\"}\n\n",
            ),
        )
        val e = withTimeout(10_000) { core(t).realtime.subscribe("t").take(1).toList() }
        assertEquals("evt_7", e.single().eventId)
        assertEquals("t", e.single().topic)
    }

    @Test fun `a head resync on a fresh connection is skipped`() = runBlocking {
        val t = FeedTransport(listOf("event: resync\ndata: {\"reason\":\"head\",\"action\":\"reread-source\"}\n\n" + ev("evt_1")))
        var called = 0
        val e = withTimeout(10_000) { core(t).realtime.subscribe("t", onResync = { called++ }).take(1).toList() }
        assertEquals("evt_1", e.single().eventId)
        assertEquals(0, called)
        assertEquals(1, t.requests.size)
    }

    @Test fun `a resync after resuming calls onResync and carries on live from a fresh connection`() = runBlocking {
        val t = FeedTransport(
            listOf(
                ev("evt_1", cursor = "c1"),
                "event: resync\ndata: {\"reason\":\"cursor-expired\",\"action\":\"reread-source\"}\n\n",
                ev("evt_1", cursor = "c9"),
            ),
        )
        val seen = mutableListOf<RealtimeResync>()
        val events = withTimeout(10_000) {
            core(t).realtime.subscribe("t", onResync = { seen += it }).take(2).toList()
        }
        assertEquals(listOf(RealtimeResync("cursor-expired", "reread-source")), seen)
        // the dedupe memory is reset by the resync, so the same id is delivered again
        assertEquals(listOf("evt_1", "evt_1"), events.map { it.eventId })
        assertEquals("c1", t.requests[1].headers["last-event-id"])
        assertNull(t.requests[2].headers["last-event-id"])
    }

    @Test fun `a resync with no onResync ends the flow`() = runBlocking {
        val t = FeedTransport(listOf(ev("evt_1", cursor = "c1"), "event: resync\ndata: {}\n\n"))
        val events = withTimeout(10_000) { core(t).realtime.subscribe("t").toList() }
        assertEquals(listOf("evt_1"), events.map { it.eventId })
        assertEquals(2, t.requests.size)
    }

    @Test fun `subscribe surfaces an error answer on open as a KError`() = runBlocking {
        val t = FeedTransport(
            emptyList(),
            429,
            """{"code":"K9O_SUBSCRIPTION_LIMIT_EXCEEDED","message":"too many","context":{},"recommendation":null}""",
        )
        val e = assertFailsWith<KError> { core(t).realtime.subscribe("t").toList() }
        assertEquals("K9O_SUBSCRIPTION_LIMIT_EXCEEDED", e.code)
        assertEquals(429, e.status)
    }

    @Test fun `subscribe refuses a bad topic and a missing project before any request`() = runBlocking {
        val t = FeedTransport(listOf(ev("evt_1")))
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).realtime.subscribe("") }.code)
        assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { core(t).realtime.subscribe("a".repeat(201)) }.code)
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertFailsWith<KError> { core(t, null).realtime.subscribe("t") }.code)
        assertTrue(t.requests.isEmpty())
    }
}
