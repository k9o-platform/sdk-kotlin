package dev.k9o.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SEARCH_URL = "https://acme.api.k9o.io/@member-portal/vectors/_vec_example/search"
private const val UPSERT_URL = "https://acme.api.k9o.io/@member-portal/vectors/_vec_example/upsert"

private const val NEAREST_OK =
    """{"data":{"results":[{"id":"p1","score":1,"_source":{"handle":"probe","chunkIndex":0},"contentHash":null,"text":null,"metadata":{},"model":null,"chunk":0},{"id":"p2","score":0.8,"_source":{"handle":"probe","chunkIndex":1},"contentHash":null,"text":null,"metadata":{},"model":null,"chunk":1}],"collection":"_vec_example"},"meta":{}}"""

class VectorsTest {
    private fun core(t: StubTransport, slug: String? = "member-portal") =
        K9OCore("https://acme.api.k9o.io/", slug, accessToken = "tok_member", transport = t)

    private fun bodyOf(t: StubTransport): JsonObject = JsonParser.parseString(t.requests.single().body).asJsonObject

    @Test
    fun `nearest posts the vector in the body and decodes results closest first`() = runBlocking {
        val t = StubTransport(body = NEAREST_OK)
        val r = core(t).vectors.nearest(
            "_vec_example", listOf(1, 0, 0), topK = 2, minScore = 0.5, metric = VectorMetric.DOT,
            filters = mapOf("metadata.lang" to "en"),
        )
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals(SEARCH_URL, req.url)
        assertEquals("Bearer tok_member", req.headers["authorization"])
        val body = bodyOf(t)
        assertEquals(listOf(1, 0, 0), body.getAsJsonArray("queryVector").map { it.asInt })
        assertEquals(2, body.get("topK").asInt)
        assertEquals(0.5, body.get("minScore").asDouble)
        assertEquals("dot", body.get("metric").asString)
        assertEquals("en", body.getAsJsonObject("filters").get("metadata.lang").asString)
        assertEquals(5, body.size())
        assertEquals("_vec_example", r.data.collection)
        assertEquals(listOf("p1", "p2"), r.data.results.map { it.id })
        assertEquals(0.8, r.data.results[1].score)
        assertNull(r.data.results[0].contentHash)
        assertNull(r.data.results[0].text)
        assertNull(r.data.results[0].model)
        assertEquals(0, r.data.results[0].chunk)
        assertEquals(VectorSource("probe", 0), r.data.results[0].source)
    }

    @Test
    fun `nearest omits optional fields`() = runBlocking {
        val t = StubTransport(body = """{"data":{"results":[],"collection":"_vec_example"},"meta":{}}""")
        core(t).vectors.nearest("_vec_example", listOf(1.0, 0.0, 0.0))
        assertEquals(setOf("queryVector"), bodyOf(t).keySet())
    }

    @Test
    fun `invalid nearest input is refused before any request`() = runBlocking {
        val t = StubTransport(body = NEAREST_OK)
        val v = core(t).vectors
        val calls: List<suspend () -> Unit> = listOf(
            { v.nearest("notes", listOf(1)) },
            { v.nearest("_x", emptyList()) },
            { v.nearest("_x", List(16001) { 0 }) },
            { v.nearest("_x", listOf(1.0, Double.NaN)) },
            { v.nearest("_x", listOf(1.0, Double.POSITIVE_INFINITY)) },
            { v.nearest("_x", listOf(1), topK = 0) },
            { v.nearest("_x", listOf(1), topK = 1001) },
            { v.nearest("_x", listOf(1), minScore = Double.NaN) },
            { v.nearest("_x", listOf(1), filters = mapOf("a" to listOf(1))) },
        )
        for (c in calls) assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { c() }.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `missing slug refused before any request`() = runBlocking {
        val t = StubTransport(body = NEAREST_OK)
        val e = assertFailsWith<KError> { core(t, slug = null).vectors.nearest("_x", listOf(1)) }
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `404 collection not found decodes into a KError`() = runBlocking {
        val t = StubTransport(
            status = 404,
            body = """{"code":"K9O_COLLECTION_NOT_FOUND","message":"derived vector collection '_vec_example' does not exist","context":{}}""",
        )
        val e = assertFailsWith<KError> { core(t).vectors.nearest("_vec_example", listOf(1, 0, 0)) }
        assertEquals("K9O_COLLECTION_NOT_FOUND", e.code)
        assertEquals(404, e.status)
    }

    @Test
    fun `400 invalid vector dimension decodes with its context`() = runBlocking {
        val t = StubTransport(
            status = 400,
            body = """{"code":"K9O_INVALID_VECTOR_DIMENSION","message":"query vector dimension does not match collection '_vec_example'","context":{"collection":"_vec_example","queryDim":2}}""",
        )
        val e = assertFailsWith<KError> { core(t).vectors.nearest("_vec_example", listOf(1, 0)) }
        assertEquals("K9O_INVALID_VECTOR_DIMENSION", e.code)
        assertEquals(400, e.status)
        assertEquals("_vec_example", e.context["collection"])
        assertEquals(2.0, (e.context["queryDim"] as Number).toDouble())
    }

    @Test
    fun `upsert posts the rows and returns the count`() = runBlocking {
        val t = StubTransport(body = """{"data":{"upserted":3,"collection":"_vec_example"},"meta":{}}""")
        val meta = JsonObject().also { it.addProperty("lang", "en") }
        val r = core(t).vectors.upsert(
            "_vec_example",
            listOf(
                VectorRow(listOf(1, 0, 0), VectorSource("probe", 0), id = "p1", text = "x", metadata = meta, subjectIds = listOf("eu_YmGE3Z6nkv5i")),
                VectorRow(listOf(0, 1, 0), VectorSource("probe", 1), contentHash = "h"),
            ),
        )
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals(UPSERT_URL, req.url)
        val rows = bodyOf(t).getAsJsonArray("rows")
        assertEquals(2, rows.size())
        val a = rows[0].asJsonObject
        assertEquals("p1", a.get("id").asString)
        assertEquals(listOf(1, 0, 0), a.getAsJsonArray("vector").map { it.asInt })
        assertEquals("probe", a.getAsJsonObject("_source").get("handle").asString)
        assertEquals(0, a.getAsJsonObject("_source").get("chunkIndex").asInt)
        assertEquals("x", a.get("text").asString)
        assertEquals("en", a.getAsJsonObject("metadata").get("lang").asString)
        assertEquals("eu_YmGE3Z6nkv5i", a.getAsJsonArray("subjectIds")[0].asString)
        val b = rows[1].asJsonObject
        assertEquals(setOf("vector", "_source", "contentHash"), b.keySet())
        assertEquals(UpsertVectorsResult(3, "_vec_example"), r.data)
    }

    @Test
    fun `invalid upsert input is refused before any request`() = runBlocking {
        val t = StubTransport()
        val v = core(t).vectors
        val src = VectorSource("h", 0)
        val calls: List<suspend () -> Unit> = listOf(
            { v.upsert("notes", listOf(VectorRow(listOf(1), src))) },
            { v.upsert("_x", emptyList()) },
            { v.upsert("_x", List(1001) { VectorRow(listOf(1), src) }) },
            { v.upsert("_x", listOf(VectorRow(emptyList(), src))) },
            { v.upsert("_x", listOf(VectorRow(listOf(Double.NaN), src))) },
        )
        for (c in calls) assertEquals("K9O_INVALID_FIELD_VALUE", assertFailsWith<KError> { c() }.code)
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `upsert 400 invalid vector dimension decodes into a KError`() = runBlocking {
        val t = StubTransport(
            status = 400,
            body = """{"code":"K9O_INVALID_VECTOR_DIMENSION","message":"rows differ in length","context":{"index":1,"expected":3,"got":2}}""",
        )
        val e = assertFailsWith<KError> { core(t).vectors.upsert("_x", listOf(VectorRow(listOf(1, 2, 3), VectorSource("h", 0)))) }
        assertEquals("K9O_INVALID_VECTOR_DIMENSION", e.code)
        assertEquals(400, e.status)
    }
}
