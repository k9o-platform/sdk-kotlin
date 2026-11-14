package dev.k9o.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Where a vector came from: a source [handle] and the [chunkIndex] of the chunk within it. [extra] holds any further keys. */
data class VectorSource(val handle: String, val chunkIndex: Int, val extra: JsonObject = JsonObject())

/**
 * One row to store with [Vectors.upsert].
 *
 * @param id 1 to 256 characters. Default: generated. Upserting an existing id overwrites that row.
 * @param vector 1 to 16000 numbers; every row in one request has the same length.
 * @param contentHash at most 128 characters.
 * @param text at most 100000 characters. Stored sealed when the row belongs to a subject.
 * @param metadata default `{}`. Only keep values here that you filter on; they are stored in plain text.
 * @param subjectIds the one end user the row belongs to (`["eu_..."]`). Required on a per-subject collection (the
 *   default for a new collection); refused on a collection declared shared.
 */
data class VectorRow(
    val vector: List<Number>,
    val source: VectorSource,
    val id: String? = null,
    val contentHash: String? = null,
    val text: String? = null,
    val metadata: JsonObject? = null,
    val subjectIds: List<String>? = null,
)

/**
 * One row returned by [Vectors.nearest]. [score] is higher for more similar rows. [model] comes from `metadata.model`
 * and [chunk] from `_source.chunkIndex`, when set.
 */
data class NearestVector(
    val id: String,
    val score: Double,
    val source: VectorSource,
    val contentHash: String?,
    val text: String?,
    val metadata: JsonObject,
    val model: String?,
    val chunk: Int?,
)

/** Result of [Vectors.nearest]: [results] closest first. */
data class NearestVectorsResult(val results: List<NearestVector>, val collection: String)

/** Result of [Vectors.upsert]. */
data class UpsertVectorsResult(val upserted: Int, val collection: String)

private const val MAX_VECTOR = 16000
private const val MAX_ROWS = 1000

private fun JsonObject.str(k: String): String? = get(k)?.takeIf { !it.isJsonNull }?.asString

private fun checkVector(vector: List<Number>, name: String) {
    if (vector.isEmpty() || vector.size > MAX_VECTOR) throw invalidValue("$name must be a list of 1 to $MAX_VECTOR numbers")
    if (vector.any { !it.toDouble().isFinite() }) throw invalidValue("$name must contain only finite numbers")
}

private fun vectorJson(vector: List<Number>): JsonArray = JsonArray().also { a -> vector.forEach { a.add(JsonPrimitive(it)) } }

private fun sourceJson(s: VectorSource): JsonObject {
    val o = JsonObject()
    for ((k, v) in s.extra.entrySet()) o.add(k, v)
    o.addProperty("handle", s.handle)
    o.addProperty("chunkIndex", s.chunkIndex)
    return o
}

private fun sourceOf(o: JsonObject?): VectorSource {
    val extra = JsonObject()
    o?.entrySet()?.forEach { (k, v) -> if (k != "handle" && k != "chunkIndex") extra.add(k, v) }
    return VectorSource(
        o?.str("handle") ?: "",
        o?.get("chunkIndex")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
        extra,
    )
}

/**
 * `vectors.*`: embeddings you computed yourself, stored next to the text and metadata they came from, and
 * nearest-neighbour search over them. Both calls send the vector in the request body, so they take real embeddings of
 * any common size (up to 16000 numbers).
 *
 * A vector collection's name starts with `_`. The first upsert creates the collection and fixes its dimension; every
 * later vector must have the same length. Searching a collection that was never upserted answers 404
 * `K9O_COLLECTION_NOT_FOUND`.
 */
class Vectors internal constructor(private val http: HttpCore) {
    private fun path(collection: String, tail: String): String {
        if (!collection.startsWith("_")) {
            throw invalidValue("a vector collection name must start with _", mapOf("collection" to collection))
        }
        return http.projectPath("vectors operations", "vectors/${encodeSegment(collection)}/$tail")
    }

    /**
     * `POST /@<projectSlug>/vectors/<collection>/search`: the rows nearest to [queryVector], closest first.
     *
     * [topK] is 1 to 1000 (the gateway defaults to 10). [minScore] keeps only results whose score is at least that.
     * [metric] defaults to cosine. [filters] are equality checks on `id`, `contentHash`, `source.<key>` and
     * `metadata.<key>` (values are String, Number or Boolean); every one must match. Null options are omitted.
     *
     * Invalid input throws [KError] `K9O_INVALID_FIELD_VALUE` before any request.
     */
    suspend fun nearest(
        collection: String,
        queryVector: List<Number>,
        topK: Int? = null,
        minScore: Double? = null,
        metric: VectorMetric? = null,
        filters: Map<String, Any>? = null,
    ): Envelope<NearestVectorsResult> {
        val where = path(collection, "search")
        checkVector(queryVector, "queryVector")
        if (topK != null && (topK < 1 || topK > 1000)) throw invalidValue("topK must be a whole number from 1 to 1000", mapOf("topK" to topK))
        if (minScore != null && !minScore.isFinite()) throw invalidValue("minScore must be a number", mapOf("minScore" to minScore))
        val body = JsonObject()
        body.add("queryVector", vectorJson(queryVector))
        if (topK != null) body.addProperty("topK", topK)
        if (minScore != null) body.addProperty("minScore", minScore)
        if (metric != null) body.addProperty("metric", metric.wire)
        if (filters != null) {
            val f = JsonObject()
            for ((k, v) in filters) {
                when (v) {
                    is String -> f.addProperty(k, v)
                    is Boolean -> f.addProperty(k, v)
                    is Number -> f.addProperty(k, v)
                    else -> throw invalidValue("filter values must be a string, number or boolean", mapOf("filter" to k))
                }
            }
            body.add("filters", f)
        }
        val env = http.post(where, body)
        val d = env.data
        val results = d.getAsJsonArray("results").map { e ->
            val o = e.asJsonObject
            NearestVector(
                id = o.get("id").asString,
                score = o.get("score").asDouble,
                source = sourceOf(o.get("_source")?.takeIf { it.isJsonObject }?.asJsonObject),
                contentHash = o.str("contentHash"),
                text = o.str("text"),
                metadata = o.get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
                model = o.str("model"),
                chunk = o.get("chunk")?.takeIf { !it.isJsonNull }?.asInt,
            )
        }
        return Envelope(NearestVectorsResult(results, d.str("collection") ?: ""), env.meta)
    }

    /**
     * `POST /@<projectSlug>/vectors/<collection>/upsert`: insert or overwrite 1 to 1000 rows in one request. The first
     * upsert creates the collection.
     *
     * Invalid input throws [KError] `K9O_INVALID_FIELD_VALUE` before any request.
     */
    suspend fun upsert(collection: String, rows: List<VectorRow>): Envelope<UpsertVectorsResult> {
        val where = path(collection, "upsert")
        if (rows.isEmpty() || rows.size > MAX_ROWS) throw invalidValue("rows must be a list of 1 to $MAX_ROWS rows")
        rows.forEachIndexed { i, r -> checkVector(r.vector, "rows[$i].vector") }
        val arr = JsonArray()
        for (r in rows) {
            val o = JsonObject()
            if (r.id != null) o.addProperty("id", r.id)
            o.add("vector", vectorJson(r.vector))
            o.add("_source", sourceJson(r.source))
            if (r.contentHash != null) o.addProperty("contentHash", r.contentHash)
            if (r.text != null) o.addProperty("text", r.text)
            if (r.metadata != null) o.add("metadata", r.metadata)
            if (r.subjectIds != null) o.add("subjectIds", JsonArray().also { a -> r.subjectIds.forEach(a::add) })
            arr.add(o)
        }
        val body = JsonObject()
        body.add("rows", arr)
        val env = http.post(where, body)
        val d = env.data
        return Envelope(UpsertVectorsResult(d.get("upserted").asInt, d.str("collection") ?: ""), env.meta)
    }
}
