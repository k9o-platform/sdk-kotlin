package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** One part of a multipart/form-data body: a text field, or a file when [filename] is set. */
class FormPart(
    val name: String,
    val bytes: ByteArray,
    val filename: String? = null,
    val contentType: String? = null,
)

/**
 * A request handed to the transport. When [parts] is set the body is multipart/form-data built from the parts
 * ([body] is null); otherwise [body] is JSON text, or null for no body.
 */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val parts: List<FormPart>? = null,
)

/** A raw response from the transport. */
class HttpResponse(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/** A response whose body is read incrementally (server-sent events). Close it to release the connection. */
class HttpStreamResponse(val status: Int, val reader: java.io.Reader, val headers: Map<String, String> = emptyMap()) : java.io.Closeable {
    override fun close() = reader.close()
}

/** Injectable transport. Throw any exception to signal a network failure. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse

    /**
     * Open a response to read incrementally. The default delivers the whole [send] body as one readable text, which
     * is enough for fixed responses; [OkHttpTransport] overrides it to read from the live connection.
     */
    suspend fun stream(request: HttpRequest): HttpStreamResponse {
        val r = send(request)
        return HttpStreamResponse(r.status, java.io.StringReader(r.body), r.headers)
    }

    /**
     * Credential-free `HEAD` to [url] WITHOUT following redirects, used once per client to learn the regional host of a
     * region-free address. Returns the `Location` of a `308` answer, else null. The default returns null (no probe), so a
     * transport that does not override it keeps sending to the configured address. Throw to signal a network failure
     * (not cached; the next call probes again).
     */
    suspend fun probe(url: String): String? = null
}

/** Default OkHttp-backed transport. [readTimeoutMillis] (default 60 s) bounds each read of a response; streams are exempt. */
class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    constructor(readTimeoutMillis: Long = DEFAULT_READ_TIMEOUT_MILLIS) : this(
        OkHttpClient.Builder().readTimeout(readTimeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
    )

    companion object {
        /** Default read timeout for a request/response call. */
        const val DEFAULT_READ_TIMEOUT_MILLIS: Long = 60_000
    }

    override suspend fun send(request: HttpRequest): HttpResponse {
        client.newCall(build(request)).execute().use { r ->
            return HttpResponse(r.code, r.body?.string() ?: "", flatten(r.headers))
        }
    }

    override suspend fun probe(url: String): String? = withContext(Dispatchers.IO) {
        val c = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val req = Request.Builder().url(url).head().header("accept", "*/*").build()
        c.newCall(req).execute().use { r -> if (r.code == 308) r.header("location") else null }
    }

    override suspend fun stream(request: HttpRequest): HttpStreamResponse = withContext(Dispatchers.IO) {
        // A live feed idles between events, so the read timeout is lifted for this call.
        val live = client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val r = live.newCall(build(request)).execute()
        val body = r.body
        HttpStreamResponse(
            r.code,
            object : java.io.InputStreamReader(body?.byteStream() ?: java.io.ByteArrayInputStream(ByteArray(0)), Charsets.UTF_8) {
                override fun close() {
                    super.close()
                    r.close()
                }
            },
            flatten(r.headers),
        )
    }

    private fun flatten(h: okhttp3.Headers): Map<String, String> {
        val m = linkedMapOf<String, String>()
        for (i in 0 until h.size) m[h.name(i).lowercase()] = h.value(i)
        return m
    }

    private fun build(request: HttpRequest): Request {
        val b = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> b.header(k, v) }
        val body: RequestBody? = when {
            request.parts != null -> MultipartBody.Builder().setType(MultipartBody.FORM).also { m ->
                for (p in request.parts) {
                    val type = p.contentType?.toMediaTypeOrNull()
                    if (p.filename != null) m.addFormDataPart(p.name, p.filename, p.bytes.toRequestBody(type))
                    else m.addFormDataPart(p.name, null, p.bytes.toRequestBody(type))
                }
            }.build()
            request.body != null -> request.body.toRequestBody("application/json".toMediaType())
            request.method in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody()
            else -> null
        }
        return b.method(request.method, body).build()
    }
}

/** Successful `{ data, meta }` envelope; `meta` is empty when absent. */
data class Envelope<T>(val data: T, val meta: Map<String, Any?>)

internal class HttpCore(
    private val configuredUrl: String,
    val projectSlug: String?,
    private val accessToken: String?,
    private val tokenProvider: (suspend () -> String?)?,
    private val transport: HttpTransport,
) {
    private val gson = GsonBuilder().serializeNulls().create()
    private val originLock = Mutex()
    private var origin: String? = null

    /**
     * The origin requests go to. A region-free org address (`<org>.api.<rest>` / `<org>.api-staging.<rest>`) refuses
     * credentials, so one credential-free probe learns the regional address and it is cached for the client's life; any
     * other address (regional, localhost, IP, internal) is used as given. A network failure is not cached.
     */
    private suspend fun resolveOrigin(): String {
        origin?.let { return it }
        return originLock.withLock {
            origin?.let { return@withLock it }
            val host = try { java.net.URI(configuredUrl).host } catch (e: Exception) { null }
            val parsed = host?.let { parseApiHost(it) }
            if (parsed == null || parsed.orgSlug == null || parsed.region != null) {
                origin = configuredUrl
                return@withLock configuredUrl
            }
            val learned = try {
                val loc = transport.probe("$configuredUrl/")
                loc?.let { regionalOriginFrom(host, it, configuredUrl) }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                return@withLock configuredUrl
            }
            val chosen = learned ?: configuredUrl
            origin = chosen
            chosen
        }
    }

    /** A 400 `K9O_INVALID_REQUEST_SHAPE` naming `context.regionalHost`: the origin to retry on once, if it is trustworthy. */
    private fun retryOrigin(e: KError, current: String): String? {
        if (e.status != 400 || e.code != "K9O_INVALID_REQUEST_SHAPE") return null
        val hint = e.context["regionalHost"] as? String ?: return null
        val host = try { java.net.URI(current).host } catch (x: Exception) { return null }
        val learned = regionalOriginFrom(host, "https://$hint", current) ?: return null
        return if (learned == current) null else learned
    }

    suspend fun post(path: String, body: JsonObject): Envelope<JsonObject> {
        val env = request("POST", path, body = body)
        val data = env.data.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw internal("Malformed envelope from ${configuredUrl + path}: expected {data, meta}", 200)
        return Envelope(data, env.meta)
    }

    /**
     * One call. [query] values are percent-encoded here; a JSON [body] or multipart [parts] is sent as given.
     * `data` is whatever JSON the gateway returned (object, array or null); a 204 gives an empty envelope.
     */
    suspend fun request(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        parts: List<FormPart>? = null,
    ): Envelope<JsonElement> {
        var base = resolveOrigin()
        var retried = false
        var url: String
        var response: HttpResponse
        var obj: JsonObject?
        while (true) {
            url = buildUrl(base, path, query)
            val hdrs = headers(parts == null && body != null, "application/json")
            response = try {
                transport.send(HttpRequest(method, url, hdrs, body?.let { gson.toJson(it) }, parts))
            } catch (e: Exception) {
                throw network(url, e)
            }
            if (response.status == 204) return Envelope(JsonNull.INSTANCE, emptyMap())
            obj = parseObject(response.body)
            if (response.status == 400 && !retried) {
                val next = retryOrigin(failure(400, obj, response.headers), base)
                if (next != null) {
                    retried = true
                    origin = next
                    base = next
                    continue
                }
            }
            break
        }
        if (response.status in 200..299) {
            if (obj == null || !obj.has("data")) {
                throw internal("Malformed envelope from $url: expected {data, meta}", response.status)
            }
            val meta = obj.get("meta")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap()
            return Envelope(obj.get("data"), meta)
        }
        throw failure(response.status, obj, response.headers)
    }

    /**
     * `GET` a public path (no project prefix) WITHOUT a credential or project header, and return the whole JSON object
     * (it is not a `{data, meta}` envelope). Used for the Rampart key feed.
     */
    suspend fun getPublicObject(path: String): JsonObject {
        val url = buildUrl(resolveOrigin(), path, emptyMap())
        val response = try {
            transport.send(HttpRequest("GET", url, mapOf("accept" to "application/json"), null))
        } catch (e: Exception) {
            throw network(url, e)
        }
        val obj = parseObject(response.body)
        if (response.status in 200..299) {
            return obj ?: throw internal("Malformed response from $url: expected a JSON object", response.status)
        }
        throw failure(response.status, obj, response.headers)
    }

    /** Open a server-sent-events response. Throws the gateway's [KError] on a non-2xx answer. */
    suspend fun openStream(
        path: String,
        query: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpStreamResponse {
        var base = resolveOrigin()
        var retried = false
        while (true) {
            val url = buildUrl(base, path, query)
            val hdrs = headers(false, "text/event-stream") + extraHeaders
            val response = try {
                transport.stream(HttpRequest("GET", url, hdrs, null))
            } catch (e: Exception) {
                throw network(url, e)
            }
            if (response.status in 200..299) return response
            val text = try {
                response.reader.use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            val err = failure(response.status, parseObject(text), response.headers)
            val next = if (retried) null else retryOrigin(err, base)
            if (next == null) throw err
            retried = true
            origin = next
            base = next
        }
    }

    private fun buildUrl(base: String, path: String, query: Map<String, String>): String {
        val qs = query.entries.joinToString("&") { (k, v) -> "${formEncode(k)}=${formEncode(v)}" }
        return base + path + if (qs.isEmpty()) "" else "?$qs"
    }

    private suspend fun headers(json: Boolean, accept: String): Map<String, String> {
        val headers = linkedMapOf("accept" to accept)
        if (json) headers["content-type"] = "application/json"
        if (!projectSlug.isNullOrEmpty()) headers["x-k9o-project-slug"] = projectSlug
        val token = accessToken ?: tokenProvider?.invoke()
        if (!token.isNullOrEmpty()) headers["authorization"] = "Bearer $token"
        return headers
    }

    private fun network(url: String, e: Exception): Exception {
        if (e is kotlin.coroutines.cancellation.CancellationException) return e
        return KError(
            code = "K9O_UPSTREAM_UNAVAILABLE",
            message = "Network error reaching $url: ${e.message ?: e.javaClass.simpleName}",
            context = mapOf("url" to url),
            recommendation = "Check connectivity to the gateway; retry with backoff if transient.",
            cause = e,
        )
    }

    private fun parseObject(text: String): JsonObject? = try {
        JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun failure(status: Int, obj: JsonObject?, headers: Map<String, String> = emptyMap()): KError {
        val headerId = headers.entries.firstOrNull { it.key.equals("x-k9o-request-id", ignoreCase = true) }?.value?.takeIf { it.isNotEmpty() }
        val code = obj?.get("code")?.takeIf { it.isJsonPrimitive }?.asString
        val message = obj?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        if (obj != null && code != null && message != null) {
            val rec = obj.get("recommendation")
            var text: String? = null
            var wait: Double? = null
            if (rec != null && rec.isJsonPrimitive && rec.asJsonPrimitive.isString) {
                text = rec.asString
            } else if (rec != null && rec.isJsonObject) {
                val ro = rec.asJsonObject
                text = ro.stringField("action") ?: ro.stringField("note")
                val w = ro.get("retryAfterSec")
                if (w != null && w.isJsonPrimitive && w.asJsonPrimitive.isNumber) {
                    val d = w.asDouble
                    if (d.isFinite() && d >= 0) wait = d
                }
            }
            return KError(
                code = code,
                message = message,
                context = obj.get("context")?.takeIf { it.isJsonObject }?.let { toMap(it.asJsonObject) } ?: emptyMap(),
                recommendation = text,
                status = status,
                requestId = obj.get("requestId")?.takeIf { it.isJsonPrimitive }?.asString ?: headerId,
                retryAfterSec = wait,
            )
        }
        return internal("Upstream returned $status with non-KError body", status, headerId)
    }

    private fun JsonObject.stringField(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun internal(message: String, status: Int, requestId: String? = null) =
        KError(code = "K9O_INTERNAL_ERROR", message = message, status = status, requestId = requestId)

    @Suppress("UNCHECKED_CAST")
    private fun toMap(o: JsonObject): Map<String, Any?> =
        Gson().fromJson(o, Map::class.java) as Map<String, Any?>
}

/** application/x-www-form-urlencoded encoding of one query component (a space becomes `+`). */
internal fun formEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

/** Percent-encoding of one path segment, as JavaScript's `encodeURIComponent` does it (a space becomes `%20`). */
internal fun encodeSegment(s: String): String {
    val keep = "-_.!~*'()"
    val sb = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xff).toChar()
        if ((c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in keep) && b >= 0) sb.append(c)
        else sb.append('%').append("%02X".format(b.toInt() and 0xff))
    }
    return sb.toString()
}

/**
 * The base URL the client uses. A given [baseUrl] is used as given (trailing slashes removed), except a platform host
 * (`api.<domain>` or `api-staging.<domain>`, no org label, no region) with [orgSlug]: that becomes
 * `<scheme>://<orgSlug>.<host>`, keeping the port. Any other host (an org host, a regional host, a custom domain,
 * localhost, an IP) is used as given and [orgSlug] is ignored. With only [orgSlug] the result is
 * `https://<orgSlug>.api.k9o.io`. With neither, `K9O_INVALID_BASE_URL`.
 */
internal fun resolveBaseUrl(baseUrl: String?, orgSlug: String?): String {
    val given = baseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }
    if (given == null) {
        if (orgSlug.isNullOrEmpty()) {
            throw KError(
                code = "K9O_INVALID_BASE_URL",
                message = "baseUrl or orgSlug is required",
                context = mapOf("field" to "baseUrl"),
                recommendation = "Pass baseUrl (for example `https://acme.api.k9o.io`) or orgSlug.",
            )
        }
        requireOrgSlug(orgSlug)
        return "https://$orgSlug.api.k9o.io"
    }
    if (orgSlug.isNullOrEmpty()) return given
    val uri = try { java.net.URI(given) } catch (e: Exception) { return given }
    val host = uri.host ?: return given
    val parsed = parseApiHost(host)
    if (parsed == null || parsed.orgSlug != null || parsed.region != null) return given
    requireOrgSlug(orgSlug)
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://$orgSlug.$host$port"
}

private fun requireOrgSlug(slug: String) {
    if (!SLUG.matches(slug) || slug.endsWith("-")) {
        throw KError(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "orgSlug \"$slug\" does not match required format ^[a-z][a-z0-9-]{1,62}\$ (must not end with '-')",
            context = mapOf("field" to "orgSlug", "slug" to slug),
            recommendation = "Pass a slug of 2 to 63 lowercase letters, digits or hyphens, starting with a letter and not ending with a hyphen.",
        )
    }
}

/** Gateway host structure: org slug (null for a platform host), `api`/`api-staging` family, 4-char region or null. */
internal class ApiHost(val orgSlug: String?, val family: String, val region: String?, val apex: String)

private val API_LABEL = Regex("^(api|api-staging)(?:-([a-z]{2}\\d{2}))?$")
private val SLUG = Regex("^[a-z][a-z0-9-]{1,62}$")

/** Read org slug and region from a gateway host without knowing its apex; null for any other shape. */
internal fun parseApiHost(host: String): ApiHost? {
    val labels = host.lowercase().split(".")
    if (labels.any { it.isEmpty() }) return null
    fun mk(label: String, org: String?, rest: List<String>): ApiHost? {
        val m = API_LABEL.matchEntire(label) ?: return null
        if (rest.isEmpty()) return null
        return ApiHost(org, m.groupValues[1], m.groupValues[2].ifEmpty { null }, rest.joinToString("."))
    }
    if (labels.size >= 3 && SLUG.matches(labels[0]) && !labels[0].endsWith("-") && API_LABEL.matches(labels[1])) {
        return mk(labels[1], labels[0], labels.drop(2))
    }
    if (labels.size >= 2 && API_LABEL.matches(labels[0])) return mk(labels[0], null, labels.drop(1))
    return null
}

/**
 * The regional origin [location] may name for the region-free [host]: https, no userinfo or port, and exactly the same
 * org, family and remainder with a region code added. Anything else is null, so a hostile redirect never gets a credential.
 */
internal fun regionalOriginFrom(host: String, location: String, base: String): String? {
    val from = parseApiHost(host) ?: return null
    if (from.orgSlug == null || from.region != null) return null
    val u = try { java.net.URI(base).resolve(location) } catch (e: Exception) { return null }
    if (u.scheme != "https" || u.userInfo != null || u.port != -1 || u.host == null) return null
    val to = parseApiHost(u.host) ?: return null
    if (to.region == null || to.orgSlug != from.orgSlug || to.family != from.family || to.apex != from.apex) return null
    return "https://${u.host}"
}
