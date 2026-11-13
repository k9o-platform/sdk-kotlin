package dev.k9o.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://acme.api.k9o.io/@member-portal/metadata"

private const val P1 =
    """{"surface":"collection:posts","scope":"posts","recordId":"p1","metadata":{"title":"Quarterly report","tags":["pinned","finance"],"priority":3,"embedding":"[1,0,0]"}}"""
private const val TELEMETRY =
    """{"surface":"telemetry:collection.metadata_index_declared","scope":"collection.metadata_index_declared","eventId":"aud_1","metadata":{"id":"aud_1","type":"collection.metadata_index_declared","actorId":"usr_1","occurredAt":"2026-10-26T20:00:00.000Z"}}"""

private fun page(vararg rows: String, next: String? = null) =
    """{"data":[${rows.joinToString(",")}],"meta":{"requestId":"r1","cursor":{"next":${if (next == null) "null" else "\"$next\""}}}}"""

class MetadataTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io/", slug, accessToken = "tok_member", transport = t)

    private fun bodyOf(t: StubTransport): JsonObject = JsonParser.parseString(t.requests.single().body).asJsonObject

    private fun refused(t: StubTransport, code: String, call: suspend () -> Unit) = runBlocking {
        assertEquals(code, assertFailsWith<KError> { call() }.code)
        assertTrue(t.requests.isEmpty(), "a request was sent")
    }

    // filter

    @Test
    fun `filter posts surfaces, prefixes fields and returns rows with the next cursor`() = runBlocking {
        val t = StubTransport(body = page(P1, next = "cur_1"))
        val r = core(t).metadata.filter(
            listOf("collection:posts"),
            where = listOf(MetadataWhere("priority", MetadataOp.GTE, 2), MetadataWhere("metadata.title", MetadataOp.STARTS_WITH, "Q")),
            limit = 1, cursor = "cur_0",
        )
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$BASE/query", req.url)
        assertEquals("Bearer tok_member", req.headers["authorization"])
        val b = bodyOf(t)
        assertEquals(setOf("surfaces", "where", "limit", "cursor"), b.keySet())
        assertEquals(listOf("collection:posts"), b.getAsJsonArray("surfaces").map { it.asString })
        assertEquals(1, b.get("limit").asInt)
        assertEquals("cur_0", b.get("cursor").asString)
        val w = b.getAsJsonArray("where").map { it.asJsonObject }
        assertEquals(listOf("metadata.priority", "metadata.title"), w.map { it.get("field").asString })
        assertEquals(listOf("gte", "startsWith"), w.map { it.get("op").asString })
        assertEquals(2, w[0].get("value").asInt)
        assertEquals("Q", w[1].get("value").asString)
        assertEquals("cur_1", r.nextCursor)
        assertEquals("p1", r.data[0].recordId)
        assertEquals(3, r.data[0].metadata.get("priority").asInt)
    }

    @Test
    fun `filter sends only the surfaces by default and reports a null cursor`() = runBlocking {
        val t = StubTransport(body = page(P1, TELEMETRY))
        val r = core(t).metadata.filter(listOf("collection:posts", "telemetry:collection.metadata_index_declared"))
        assertEquals(setOf("surfaces"), bodyOf(t).keySet())
        assertNull(r.nextCursor)
        assertEquals("aud_1", r.data[1].eventId)
        assertNull(r.data[1].recordId)
    }

    @Test
    fun `filter leaves createdAt and actorId bare on a telemetry surface`() = runBlocking {
        val t = StubTransport(body = page(TELEMETRY))
        core(t).metadata.filter(
            listOf("telemetry:collection."),
            where = listOf(MetadataWhere("createdAt", MetadataOp.GT, "2026-10-26T00:00:00Z"), MetadataWhere("actorId", MetadataOp.EQ, "usr_1")),
        )
        assertEquals(listOf("createdAt", "actorId"), bodyOf(t).getAsJsonArray("where").map { it.asJsonObject.get("field").asString })
    }

    @Test
    fun `filter lets a storage surface through and decodes the 501`() = runBlocking {
        val t = StubTransport(
            status = 501,
            body = """{"code":"K9O_NOT_IMPLEMENTED","message":"storage metadata is not available","context":{"surface":"storage:photos"}}""",
        )
        val e = assertFailsWith<KError> { core(t).metadata.filter(listOf("storage:photos")) }
        assertEquals(listOf("storage:photos"), bodyOf(t).getAsJsonArray("surfaces").map { it.asString })
        assertEquals("K9O_NOT_IMPLEMENTED", e.code)
        assertEquals(501, e.status)
        assertEquals("storage:photos", e.context["surface"])
    }

    @Test
    fun `filter decodes 413, 400 not indexed and 403`() = runBlocking {
        val big = StubTransport(
            status = 413,
            body = """{"code":"K9O_QUERY_RESULT_TOO_LARGE","message":"too many rows","context":{"cap":10000,"surfaces":["collection:posts"]},"recommendation":"Add where or lower limit."}""",
        )
        val e413 = assertFailsWith<KError> { core(big).metadata.filter(listOf("collection:posts")) }
        assertEquals("K9O_QUERY_RESULT_TOO_LARGE", e413.code)
        assertEquals(413, e413.status)
        assertEquals(10000.0, (e413.context["cap"] as Number).toDouble())
        assertEquals("Add where or lower limit.", e413.recommendation)

        val nope = StubTransport(
            status = 400,
            body = """{"code":"K9O_METADATA_FIELD_NOT_INDEXED","message":"not declared","context":{"surface":"collection:posts","field":"metadata.secret"},"recommendation":{"action":"declare it"}}""",
        )
        val e400 = assertFailsWith<KError> {
            core(nope).metadata.filter(listOf("collection:posts"), where = listOf(MetadataWhere("secret", MetadataOp.EQ, 1)))
        }
        assertEquals("K9O_METADATA_FIELD_NOT_INDEXED", e400.code)
        assertEquals("metadata.secret", e400.context["field"])
        assertEquals("declare it", e400.recommendation)

        val denied = StubTransport(status = 403, body = """{"code":"K9O_EXTENSION_PERMISSION_DENIED","message":"no grant","context":{}}""")
        val e403 = assertFailsWith<KError> { core(denied).metadata.filter(listOf("collection:posts")) }
        assertEquals("K9O_EXTENSION_PERMISSION_DENIED", e403.code)
        assertEquals(403, e403.status)
    }

    @Test
    fun `filter refuses bad input before any request`() {
        val t = StubTransport(body = page(P1))
        val m = core(t).metadata
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(emptyList()) }
        refused(t, "K9O_UNKNOWN_SURFACE") { m.filter(listOf("posts")) }
        refused(t, "K9O_UNKNOWN_SURFACE") { m.filter(listOf("table:posts")) }
        refused(t, "K9O_UNKNOWN_SURFACE") { m.filter(listOf("collection:")) }
        refused(t, "K9O_UNKNOWN_SURFACE") { m.filter(listOf("collection:1posts")) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(listOf("collection:posts", "telemetry:x"), cursor = "c") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(listOf("collection:posts"), limit = 0) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(listOf("collection:posts"), limit = 1001) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(listOf("collection:posts"), cursor = "") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.filter(listOf("collection:posts"), where = listOf(MetadataWhere("", MetadataOp.EQ, 1))) }
    }

    @Test
    fun `missing slug is refused before any request`() {
        val t = StubTransport(body = page(P1))
        refused(t, "K9O_MISSING_PROJECT_CONTEXT") { core(t, slug = null).metadata.filter(listOf("collection:posts")) }
        refused(t, "K9O_MISSING_PROJECT_CONTEXT") { core(t, slug = null).metadata.index.get("posts") }
    }

    // set

    @Test
    fun `set patches the bare columns and returns the whole row`() = runBlocking {
        val t = StubTransport(body = """{"data":{"id":"p2","title":"Team offsite 2027","priority":9,"secret":"s2"},"meta":{"requestId":"r2"}}""")
        val r = core(t).metadata.set("posts", "p 2", mapOf("title" to "Team offsite 2027", "metadata.priority" to 9))
        val req = t.requests.single()
        assertEquals("PATCH", req.method)
        assertEquals("$BASE/collection/posts/p%202", req.url)
        val b = bodyOf(t)
        assertEquals(setOf("metadata"), b.keySet())
        assertEquals(setOf("title", "priority"), b.getAsJsonObject("metadata").keySet())
        assertEquals("Team offsite 2027", b.getAsJsonObject("metadata").get("title").asString)
        assertEquals(9, b.getAsJsonObject("metadata").get("priority").asInt)
        assertEquals("p2", r.data.get("id").asString)
        assertEquals("s2", r.data.get("secret").asString)
    }

    @Test
    fun `set refuses bad input before any request`() {
        val t = StubTransport(body = """{"data":{},"meta":{}}""")
        val m = core(t).metadata
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.set("bad-name", "p1", mapOf("a" to 1)) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.set("posts", "", mapOf("a" to 1)) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.set("posts", "p1", emptyMap()) }
    }

    @Test
    fun `set decodes the not declared answer`() = runBlocking {
        val t = StubTransport(status = 400, body = """{"code":"K9O_METADATA_FIELD_NOT_INDEXED","message":"x","context":{"field":"metadata.nope"}}""")
        assertEquals("K9O_METADATA_FIELD_NOT_INDEXED", assertFailsWith<KError> { core(t).metadata.set("posts", "p1", mapOf("nope" to 1)) }.code)
    }

    // search

    @Test
    fun `search posts surface, prefixed field, q and limit`() = runBlocking {
        val t = StubTransport(body = page("""{"surface":"collection:posts","scope":"posts","recordId":"p3","metadata":{"title":"Budget draft"}}"""))
        val r = core(t).metadata.search("collection:posts", "body", "finance", limit = 5)
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$BASE/search", req.url)
        val b = bodyOf(t)
        assertEquals(setOf("surface", "field", "q", "limit"), b.keySet())
        assertEquals("metadata.body", b.get("field").asString)
        assertEquals("finance", b.get("q").asString)
        assertEquals(5, b.get("limit").asInt)
        assertEquals(listOf("p3"), r.data.map { it.recordId })
        assertNull(r.nextCursor)
    }

    @Test
    fun `search keeps an existing prefix and omits limit`() = runBlocking {
        val t = StubTransport(body = page())
        core(t).metadata.search("collection:posts", "metadata.body", "x")
        assertEquals(setOf("surface", "field", "q"), bodyOf(t).keySet())
        assertEquals("metadata.body", bodyOf(t).get("field").asString)
    }

    @Test
    fun `search refuses bad input before any request and decodes the encrypted answer`() = runBlocking {
        val t = StubTransport(body = page())
        val m = core(t).metadata
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.search("telemetry:x", "body", "a") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.search("storage:b", "body", "a") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.search("collection:posts", "", "a") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.search("collection:posts", "body", "") }
        refused(t, "K9O_INVALID_FIELD_VALUE") { m.search("collection:posts", "body", "a", limit = 2000) }

        val enc = StubTransport(
            status = 400,
            body = """{"code":"K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED","message":"x","context":{"column":"body","op":"textSearch"}}""",
        )
        assertEquals(
            "K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED",
            assertFailsWith<KError> { core(enc).metadata.search("collection:posts", "body", "a") }.code,
        )
    }

    // vectors.nearest

    @Test
    fun `nearest posts the prefixed column, the embedding and the options`() = runBlocking {
        val t = StubTransport(body = page("""{"surface":"collection:posts","scope":"posts","recordId":"p1","metadata":{"title":"Quarterly report","embedding":"[1,0,0]"}}"""))
        val r = core(t).metadata.vectors.nearest(
            "collection:posts", "embedding", listOf(1, 0, 0), k = 3, metric = VectorMetric.DOT,
            where = listOf(MetadataWhere("priority", MetadataOp.GT, 1)),
        )
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$BASE/vectors/nearest", req.url)
        val b = bodyOf(t)
        assertEquals(setOf("surface", "column", "embedding", "k", "metric", "where"), b.keySet())
        assertEquals("metadata.embedding", b.get("column").asString)
        assertEquals(listOf(1, 0, 0), b.getAsJsonArray("embedding").map { it.asInt })
        assertEquals(3, b.get("k").asInt)
        assertEquals("dot", b.get("metric").asString)
        assertEquals("metadata.priority", b.getAsJsonArray("where")[0].asJsonObject.get("field").asString)
        assertEquals("p1", r.data[0].recordId)
    }

    @Test
    fun `nearest sends only the required fields by default`() = runBlocking {
        val t = StubTransport(body = page())
        core(t).metadata.vectors.nearest("collection:posts", "metadata.embedding", listOf(0.5))
        assertEquals(setOf("surface", "column", "embedding"), bodyOf(t).keySet())
        assertEquals("metadata.embedding", bodyOf(t).get("column").asString)
    }

    @Test
    fun `nearest refuses bad input before any request`() {
        val t = StubTransport(body = page())
        val v = core(t).metadata.vectors
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "e", emptyList()) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "e", listOf(1.0, Double.NaN)) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "e", listOf(Double.POSITIVE_INFINITY)) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "e", listOf(1), k = 0) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "e", listOf(1), k = 1001) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("telemetry:x", "e", listOf(1)) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { v.nearest("collection:posts", "", listOf(1)) }
    }

    // index

    private val indexBody = """{"data":{"collection":"posts","columns":[{"column":"body","indexed":false},{"column":"tags","indexed":true}]},"meta":{}}"""

    @Test
    fun `index get reads the declaration`() = runBlocking {
        val t = StubTransport(body = indexBody)
        val r = core(t).metadata.index.get("posts")
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals("$BASE/index/posts", req.url)
        assertNull(req.body)
        assertEquals("posts", r.data.collection)
        assertEquals(MetadataIndexColumn("tags", true), r.data.columns[1])
    }

    @Test
    fun `index declare sends PUT and accepts an empty list and 64 columns`() = runBlocking {
        val t = StubTransport(body = indexBody)
        val m = core(t).metadata
        m.index.declare("posts", listOf(MetadataColumn("body"), MetadataColumn("tags", indexed = true)))
        m.index.declare("posts", emptyList())
        m.index.declare("posts", List(64) { MetadataColumn("c$it") })
        assertEquals(listOf("PUT", "PUT", "PUT"), t.requests.map { it.method })
        assertEquals("$BASE/index/posts", t.requests[0].url)
        val cols = JsonParser.parseString(t.requests[0].body).asJsonObject.getAsJsonArray("columns").map { it.asJsonObject }
        assertEquals(listOf("body", "tags"), cols.map { it.get("column").asString })
        assertEquals(setOf("column"), cols[0].keySet())
        assertEquals(true, cols[1].get("indexed").asBoolean)
        assertEquals(0, JsonParser.parseString(t.requests[1].body).asJsonObject.getAsJsonArray("columns").size())
        assertEquals(64, JsonParser.parseString(t.requests[2].body).asJsonObject.getAsJsonArray("columns").size())
    }

    @Test
    fun `index refuses bad input before any request`() {
        val t = StubTransport(body = indexBody)
        val i = core(t).metadata.index
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.declare("posts", List(65) { MetadataColumn("c$it") }) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.declare("posts", listOf(MetadataColumn("id"))) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.declare("posts", listOf(MetadataColumn("a"), MetadataColumn("a"))) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.declare("posts", listOf(MetadataColumn("a b"))) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.declare("bad-name", emptyList()) }
        refused(t, "K9O_INVALID_FIELD_VALUE") { i.get("bad-name") }
    }

    @Test
    fun `index decodes a missing collection and a refused role`() = runBlocking {
        val missing = StubTransport(status = 404, body = """{"code":"K9O_COLLECTION_NOT_FOUND","message":"x"}""")
        assertEquals(404, assertFailsWith<KError> { core(missing).metadata.index.declare("ghost", emptyList()) }.status)
        val denied = StubTransport(status = 403, body = """{"code":"K9O_IAM_POLICY_DENIED","message":"x"}""")
        assertEquals("K9O_IAM_POLICY_DENIED", assertFailsWith<KError> { core(denied).metadata.index.declare("posts", emptyList()) }.code)
    }
}
