package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * Comparison operators of a `where` clause. [AND], [OR] and [NOT] are part of the grammar, but the gateway refuses them
 * today (400 `K9O_INVALID_FIELD_VALUE` on a collection surface, `K9O_METADATA_FIELD_NOT_INDEXED` on telemetry).
 */
enum class MetadataOp(internal val wire: String) {
    EQ("eq"), NEQ("neq"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), CONTAINS("contains"),
    STARTS_WITH("startsWith"), ENDS_WITH("endsWith"), IN("in"), NOT_IN("notIn"), AND("and"), OR("or"), NOT("not"),
}

/**
 * One condition. On a `collection:` surface [field] is a declared column (`priority` or `metadata.priority`; the prefix is
 * added when it is missing) and each field may appear once. On a `telemetry:` surface the fields are `createdAt` (with
 * `GT`, `GTE`, `LT`, `LTE`) and `actorId` (with `EQ`). [value] is a String, Number, Boolean, null, a List or a Gson element.
 */
data class MetadataWhere(val field: String, val op: MetadataOp, val value: Any? = null)

/**
 * One result row. A `collection:` row has [recordId]; a `telemetry:` row has [eventId]. [metadata] holds the declared
 * columns that are present on the row, under their bare names (an undeclared column never appears).
 */
data class MetadataRow(
    val surface: String,
    val scope: String,
    val recordId: String?,
    val eventId: String?,
    val metadata: JsonObject,
)

/** One declared column. [indexed] defaults to false when null. */
data class MetadataColumn(val column: String, val indexed: Boolean? = null)

/** A collection's declaration: its declared columns, ordered by name. */
data class MetadataIndex(val collection: String, val columns: List<MetadataIndexColumn>)

data class MetadataIndexColumn(val column: String, val indexed: Boolean)

/** The cursor of the next page (`meta["cursor"]["next"]`), or null on the last page. */
val <T> Envelope<T>.nextCursor: String?
    get() = ((meta["cursor"] as? Map<*, *>)?.get("next")) as? String

private const val PREFIX = "metadata."
private const val MAX_COLUMNS = 64
private const val MAX_LIMIT = 1000
private val SLICES = setOf("collection", "telemetry", "storage")
private val SCOPE = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val IDENT = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$")

/** On a telemetry surface these where fields are sent bare, never prefixed. */
private val TELEMETRY_FIELDS = setOf("createdAt", "actorId")
private val gson = Gson()

private fun checkSurface(surface: String, name: String) {
    val at = surface.indexOf(':')
    val slice = if (at < 0) "" else surface.substring(0, at)
    val scope = if (at < 0) "" else surface.substring(at + 1)
    if (slice !in SLICES || !SCOPE.matches(scope) || (slice == "collection" && !IDENT.matches(scope))) {
        throw KError(
            code = "K9O_UNKNOWN_SURFACE",
            message = "$name \"$surface\" is not a surface: use collection:<name>, telemetry:<event> or storage:<bucket>",
            context = mapOf("surface" to surface),
        )
    }
}

private fun checkCollectionSurface(surface: String, name: String) {
    checkSurface(surface, name)
    if (!surface.startsWith("collection:")) {
        throw invalidValue("$name must be a collection surface (collection:<name>)", mapOf("surface" to surface))
    }
}

private fun checkLimit(value: Int?, name: String) {
    if (value != null && (value < 1 || value > MAX_LIMIT)) {
        throw invalidValue("$name must be a whole number from 1 to $MAX_LIMIT", mapOf(name to value))
    }
}

/** The name on the wire: `metadata.<name>`, adding the prefix when it is missing. */
private fun prefixed(name: String, label: String): String {
    if (name.isEmpty() || name == PREFIX) throw invalidValue("$label must be a column name", mapOf(label to name))
    return if (name.startsWith(PREFIX)) name else PREFIX + name
}

private fun wireWhere(where: List<MetadataWhere>?, telemetry: Boolean): JsonArray? {
    if (where == null) return null
    val out = JsonArray()
    where.forEachIndexed { i, c ->
        val bare = telemetry && c.field in TELEMETRY_FIELDS
        val o = JsonObject()
        o.addProperty("field", if (bare) c.field else prefixed(c.field, "where[$i].field"))
        o.addProperty("op", c.op.wire)
        if (c.value != null) o.add("value", toJson(c.value))
        out.add(o)
    }
    return out
}

private fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull.INSTANCE
    is JsonElement -> v
    else -> gson.toJsonTree(v)
}

private fun collectionName(collection: String): String {
    if (!IDENT.matches(collection)) throw invalidValue("collection must be a collection name", mapOf("collection" to collection))
    return collection
}

private fun rowsOf(env: Envelope<JsonElement>): Envelope<List<MetadataRow>> {
    val rows = (env.data.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).map { e ->
        val o = e.asJsonObject
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString
        MetadataRow(
            surface = s("surface") ?: "",
            scope = s("scope") ?: "",
            recordId = s("recordId"),
            eventId = s("eventId"),
            metadata = o.get("metadata")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject(),
        )
    }
    return Envelope(rows, env.meta)
}

/**
 * `metadata.*`: columns you declare on a collection (or on another data surface) so they can be filtered, searched and
 * matched by vector through one API, with the same access rules as the data. This client has no realtime, so there is no
 * `subscribe`.
 *
 * Declare the columns first ([MetadataIndexes.declare]). [set] writes declared columns only; [filter], [search] and
 * [MetadataVectors.nearest] read declared columns only. Column names given to [filter] (`where` fields), [search] (`field`)
 * and [MetadataVectors.nearest] (`column`) are sent as `metadata.<name>`; the prefix is added when you leave it out. Rows
 * come back with bare column names under `metadata`. Page [filter] with [nextCursor] on the result.
 *
 * Errors arrive as [KError] with the gateway's code:
 * - 400 `K9O_UNKNOWN_SURFACE`: the surface is not `collection:<name>`, `telemetry:<event>` or `storage:<bucket>`.
 * - 400 `K9O_METADATA_FIELD_NOT_INDEXED`: the column is not declared.
 * - 400 `K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED`: an encrypted column takes only `EQ` (not null) and `IN`.
 * - 403 `K9O_EXTENSION_PERMISSION_DENIED`: an extension token lacks a grant for the surface.
 * - 413 `K9O_QUERY_RESULT_TOO_LARGE`: more than 10000 rows across the surfaces.
 * - 501 `K9O_NOT_IMPLEMENTED`: `storage:` surfaces are accepted by the client and answered with 501.
 */
class Metadata internal constructor(private val http: HttpCore) {
    /** Nearest-vector search over a declared vector column. */
    val vectors: MetadataVectors = MetadataVectors(http)

    /** Read and replace the declaration of a collection's metadata columns. */
    val index: MetadataIndexes = MetadataIndexes(http)

    /**
     * `POST /@<projectSlug>/metadata/query`: rows of one or more [surfaces] whose declared columns meet [where], in request
     * order. Pages with [cursor] when exactly one surface is named ([nextCursor] on the result, null on the last page).
     * [limit] is 1 to 1000 per surface (the gateway defaults to 100). More than 10000 rows answers 413
     * `K9O_QUERY_RESULT_TOO_LARGE`; a `storage:` surface answers 501 `K9O_NOT_IMPLEMENTED`.
     *
     * Invalid input throws [KError] before any request.
     */
    suspend fun filter(
        surfaces: List<String>,
        where: List<MetadataWhere>? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): Envelope<List<MetadataRow>> {
        val path = http.projectPath("metadata operations", "metadata/query")
        if (surfaces.isEmpty()) throw invalidValue("surfaces must be a non-empty list")
        surfaces.forEachIndexed { i, s -> checkSurface(s, "surfaces[$i]") }
        checkLimit(limit, "limit")
        if (cursor != null) {
            if (cursor.isEmpty()) throw invalidValue("cursor must be the cursor of a previous page")
            if (surfaces.size != 1) throw invalidValue("cursor is allowed only with exactly one surface", mapOf("field" to "cursor"))
        }
        val wire = wireWhere(where, surfaces.any { it.startsWith("telemetry:") })
        val body = JsonObject()
        body.add("surfaces", JsonArray().also { a -> surfaces.forEach { a.add(it) } })
        if (wire != null) body.add("where", wire)
        if (limit != null) body.addProperty("limit", limit)
        if (cursor != null) body.addProperty("cursor", cursor)
        return rowsOf(http.request("POST", path, body = body))
    }

    /**
     * `PATCH /@<projectSlug>/metadata/collection/<collection>/<id>`: write declared columns of one record. Keys of
     * [metadata] are bare column names (a leading `metadata.` is dropped); a column that is not declared answers 400
     * `K9O_METADATA_FIELD_NOT_INDEXED`. Returns the whole updated record. The write follows the record API's rules, so a
     * write that needs approval is queued there.
     *
     * Invalid input throws [KError] before any request.
     */
    suspend fun set(collection: String, id: String, metadata: Map<String, Any?>): Envelope<JsonObject> {
        val name = collectionName(collection)
        if (id.isEmpty()) throw invalidValue("id must be a record id", mapOf("id" to id))
        if (metadata.isEmpty()) throw invalidValue("metadata must be a map with at least one column")
        val path = http.projectPath("metadata operations", "metadata/collection/${encodeSegment(name)}/${encodeSegment(id)}")
        val bare = JsonObject()
        for ((k, v) in metadata) bare.add(if (k.startsWith(PREFIX)) k.substring(PREFIX.length) else k, toJson(v))
        val body = JsonObject().also { it.add("metadata", bare) }
        val env = http.request("PATCH", path, body = body)
        val row = env.data.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        return Envelope(row, env.meta)
    }

    /**
     * `POST /@<projectSlug>/metadata/search`: full-text search over one declared text column of a collection. Matching is
     * by word stem, so "finance" does not match "financial". Returns the first page only (there is no cursor input) and no
     * rank. [limit] is 1 to 1000 (the gateway defaults to 50).
     *
     * Invalid input throws [KError] before any request.
     */
    suspend fun search(surface: String, field: String, q: String, limit: Int? = null): Envelope<List<MetadataRow>> {
        val path = http.projectPath("metadata operations", "metadata/search")
        checkCollectionSurface(surface, "surface")
        val wireField = prefixed(field, "field")
        if (q.isEmpty()) throw invalidValue("q must be a non-empty string")
        checkLimit(limit, "limit")
        val body = JsonObject()
        body.addProperty("surface", surface)
        body.addProperty("field", wireField)
        body.addProperty("q", q)
        if (limit != null) body.addProperty("limit", limit)
        return rowsOf(http.request("POST", path, body = body))
    }
}

/** `metadata.vectors.*`. */
class MetadataVectors internal constructor(private val http: HttpCore) {
    /**
     * `POST /@<projectSlug>/metadata/vectors/nearest`: the rows whose declared vector column is nearest to [embedding],
     * closest first. Rows carry no score. At most 50 rows come back whatever [k] is, until the gateway forwards a larger
     * page size. [k] is 1 to 1000 (the gateway defaults to 10); [metric] defaults to cosine.
     *
     * Invalid input throws [KError] before any request.
     */
    suspend fun nearest(
        surface: String,
        column: String,
        embedding: List<Number>,
        k: Int? = null,
        metric: VectorMetric? = null,
        where: List<MetadataWhere>? = null,
    ): Envelope<List<MetadataRow>> {
        val path = http.projectPath("metadata operations", "metadata/vectors/nearest")
        checkCollectionSurface(surface, "surface")
        val wireColumn = prefixed(column, "column")
        if (embedding.isEmpty()) throw invalidValue("embedding must be a non-empty list of numbers")
        if (embedding.any { !it.toDouble().isFinite() }) throw invalidValue("embedding must contain only finite numbers")
        checkLimit(k, "k")
        val wire = wireWhere(where, false)
        val body = JsonObject()
        body.addProperty("surface", surface)
        body.addProperty("column", wireColumn)
        body.add("embedding", JsonArray().also { a -> embedding.forEach { a.add(JsonPrimitive(it)) } })
        if (k != null) body.addProperty("k", k)
        if (metric != null) body.addProperty("metric", metric.wire)
        if (wire != null) body.add("where", wire)
        return rowsOf(http.request("POST", path, body = body))
    }
}

/** `metadata.index.*`. */
class MetadataIndexes internal constructor(private val http: HttpCore) {
    private fun indexOf(env: Envelope<JsonElement>): Envelope<MetadataIndex> {
        val o = env.data.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val cols = (o.get("columns")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).map { e ->
            val c = e.asJsonObject
            MetadataIndexColumn(
                c.get("column")?.takeIf { !it.isJsonNull }?.asString ?: "",
                c.get("indexed")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
            )
        }
        return Envelope(MetadataIndex(o.get("collection")?.takeIf { !it.isJsonNull }?.asString ?: "", cols), env.meta)
    }

    /** `GET /@<projectSlug>/metadata/index/<collection>`: the columns declared on a collection, ordered by name. */
    suspend fun get(collection: String): Envelope<MetadataIndex> {
        val name = collectionName(collection)
        val path = http.projectPath("metadata operations", "metadata/index/${encodeSegment(name)}")
        return indexOf(http.request("GET", path))
    }

    /**
     * `PUT /@<projectSlug>/metadata/index/<collection>`: declare the columns of a collection that metadata may use. The
     * columns must already exist on the collection. The list replaces the previous declaration (an empty list clears it);
     * at most 64 columns, no `id`, no duplicates. Needs the project-admin role and the migrations permission.
     *
     * Invalid input throws [KError] before any request.
     */
    suspend fun declare(collection: String, columns: List<MetadataColumn>): Envelope<MetadataIndex> {
        val name = collectionName(collection)
        val path = http.projectPath("metadata operations", "metadata/index/${encodeSegment(name)}")
        if (columns.size > MAX_COLUMNS) throw invalidValue("at most $MAX_COLUMNS columns can be declared", mapOf("count" to columns.size))
        val seen = HashSet<String>()
        val list = JsonArray()
        columns.forEachIndexed { i, c ->
            if (!IDENT.matches(c.column) || c.column == "id") {
                throw invalidValue("columns[$i].column must be a column name other than id", mapOf("column" to c.column))
            }
            if (!seen.add(c.column)) throw invalidValue("column \"${c.column}\" is listed twice", mapOf("column" to c.column))
            val o = JsonObject()
            o.addProperty("column", c.column)
            if (c.indexed != null) o.addProperty("indexed", c.indexed)
            list.add(o)
        }
        val body = JsonObject().also { it.add("columns", list) }
        return indexOf(http.request("PUT", path, body = body))
    }
}
