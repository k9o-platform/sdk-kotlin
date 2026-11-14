package dev.k9o.gate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

private val JSON = "application/json; charset=utf-8".toMediaType()
private val gson = Gson()

/**
 * Internal OkHttp transport layer for K9OGate.
 *
 * Requests go to `<origin>/@<projectSlug>/gate/<suffix>` (`auth` replaces `gate` for [postAuthorized]). A region-free org address
 * (`<org>.api.<rest>` or `<org>.api-staging.<rest>`) refuses requests that carry a body or credentials,
 * so one credential-free probe learns the regional address, which is then used for every request.
 * Any other address (regional, localhost, IP, internal) is used as given.
 * All requests are dispatched on `Dispatchers.IO`.
 */
internal class GateAuthClient(
    private val orgBaseUrl: String,
    private val projectSlug: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val probe: (suspend (url: String) -> String?)? = defaultProbe(httpClient),
    /** True for `errors = "redacted"`: failures carry no gateway text, see [GateError]. */
    private val redacted: Boolean = false,
) {
    private val originLock = Mutex()
    private var origin: String? = null

    /**
     * The origin requests go to. Decided once per client: a definite answer is kept for the client's life,
     * a network failure is not kept (the next call asks again) and falls back to the configured address.
     */
    private suspend fun resolveOrigin(): String {
        origin?.let { return it }
        return originLock.withLock {
            origin?.let { return@withLock it }
            val host = try { URI(orgBaseUrl).host } catch (e: Exception) { null }
            val parsed = host?.let { parseApiHost(it) }
            if (host == null || parsed == null || parsed.orgSlug == null || parsed.region != null || probe == null) {
                origin = orgBaseUrl
                return@withLock orgBaseUrl
            }
            val learned = try {
                probe.invoke("$orgBaseUrl/")?.let { regionalOriginFrom(host, it, orgBaseUrl) }
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                return@withLock orgBaseUrl
            }
            val chosen = learned ?: orgBaseUrl
            origin = chosen
            chosen
        }
    }

    private suspend fun url(suffix: String, origin: String? = null, area: String = "gate") =
        "${origin ?: resolveOrigin()}/@$projectSlug/$area/$suffix"

    /**
     * Ask the project which region holds [email] and return the origin the credential goes to: the located origin if it
     * passes the rule in `Locate.kt`, else the base. A 404 means the base. Any other non-2xx answer (429, 503, anything else) is raised like any other
     * gateway error and nothing is retried; a 2xx with a malformed body means the base.
     */
    suspend fun locate(email: String): String {
        val base = resolveOrigin()
        val answer: LocateAnswer? = try {
            post<LocateBody, LocateAnswer>("locate", LocateBody(email), base)
        } catch (e: GateError.Decoding) {
            null
        } catch (e: GateError.Unknown) {
            // A door that is switched off answers 404: no located origin, so the base is used. Every other failure raises.
            if (e.statusCode == 404) return base
            throw e
        }
        return chooseLocatedOrigin(base, answer?.region, answer?.origin)
    }

    /** The origin recorded for a session's refresh token, else the base. A hint only: a stale one gets a 401. */
    suspend fun originFor(hint: String?): String = hint ?: resolveOrigin()

    suspend inline fun <reified B, reified R : Any> post(
        path: String,
        body: B,
        origin: String? = null,
    ): R = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(body).toRequestBody(JSON)
        val request = Request.Builder()
            .url(url(path, origin))
            .post(requestBody)
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
            .build()
        execute(request)
    }

    /**
     * POST to `<origin>/@<projectSlug>/gate/<path>`. A null [body] sends an empty body; a null [bearerToken] sends no
     * `Authorization` header.
     */
    suspend inline fun <reified B, reified R : Any> postBearer(
        path: String,
        body: B?,
        bearerToken: String?,
        origin: String? = null,
    ): R = withContext(Dispatchers.IO) {
        val requestBody = if (body == null) ByteArray(0).toRequestBody(null) else gson.toJson(body).toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url(path, origin))
            .post(requestBody)
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
        if (bearerToken != null) builder.header("Authorization", "Bearer $bearerToken")
        execute(builder.build())
    }

    /** POST with no body and an `Authorization: Bearer` header, to `<origin>/@<projectSlug>/auth/<path>`. */
    suspend inline fun <reified R : Any> postAuthorized(
        path: String,
        bearerToken: String,
        origin: String? = null,
    ): R = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path, origin, "auth"))
            .post(ByteArray(0).toRequestBody(null))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
            .build()
        execute(request)
    }

    /**
     * DELETE `<origin>/@<projectSlug>/gate/<path>` with an `Authorization: Bearer` header. A null [body] sends no body.
     */
    suspend inline fun <reified B, reified R : Any> deleteBearer(
        path: String,
        body: B?,
        bearerToken: String,
    ): R = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url(path))
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
        if (body == null) builder.delete() else builder.delete(gson.toJson(body).toRequestBody(JSON))
        execute(builder.build())
    }

    suspend inline fun <reified R : Any> get(
        path: String,
        bearerToken: String,
    ): R = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .get()
            .header("Authorization", "Bearer $bearerToken")
            .header("Accept", "application/json")
            .header("User-Agent", "k9o-gate-kotlin")
            .build()
        execute(request)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified R : Any> execute(request: Request): R {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw networkError(e, redacted)
        }
        val bodyString = response.body?.string()
        if (!response.isSuccessful) {
            throw gateErrorFromResponse(response.code, bodyString, response.header(REQUEST_ID_HEADER), redacted)
        }
        return try {
            val envelopeType = object : TypeToken<Envelope<R>>() {}.type
            val envelope = gson.fromJson<Envelope<R>>(bodyString, envelopeType)
            envelope.data
        } catch (e: Exception) {
            throw decodingError(e, redacted)
        }
    }
}

// MARK: - Request body shapes (internal)

internal data class SignInBody(val email: String, val password: String)
/** The refresh body is closed to `{ refreshToken }`; the gateway refuses any other field. */
internal data class RefreshTokenBody(val refreshToken: String)
internal data class SignOutBody(val refreshToken: String)
internal data class SignOutResponse(val revoked: Boolean)
internal data class RevokeAllResponse(val revokedCount: Int)

/**
 * The org base URL the client uses. A given [orgBaseUrl] is used as given (trailing slashes removed), except a platform
 * host (`api.<domain>` or `api-staging.<domain>`, no org label, no region) with [orgSlug]: that becomes
 * `<scheme>://<orgSlug>.<host>`, keeping the port. Any other host (an org host, a regional host, a custom domain,
 * localhost, an IP) is used as given and [orgSlug] is ignored. With only [orgSlug] the result is
 * `https://<orgSlug>.api.k9o.io`. With neither, [K9OGateConfigException] (`K9O_INVALID_BASE_URL`).
 */
internal fun resolveOrgBaseUrl(orgBaseUrl: String?, orgSlug: String?): String {
    val given = orgBaseUrl?.trimEnd('/')?.takeIf { it.isNotBlank() }
    if (given == null) {
        if (orgSlug.isNullOrEmpty()) {
            throw K9OGateConfigException(
                code = "K9O_INVALID_BASE_URL",
                message = "orgBaseUrl or orgSlug is required",
                context = mapOf("field" to "baseUrl"),
            )
        }
        requireOrgSlug(orgSlug)
        return "https://$orgSlug.api.k9o.io"
    }
    if (orgSlug.isNullOrEmpty()) return given
    val uri = try { URI(given) } catch (e: Exception) { return given }
    val host = uri.host ?: return given
    val parsed = parseApiHost(host)
    if (parsed == null || parsed.orgSlug != null || parsed.region != null) return given
    requireOrgSlug(orgSlug)
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "${uri.scheme}://$orgSlug.$host$port"
}

private fun requireOrgSlug(slug: String) {
    if (!SLUG.matches(slug) || slug.endsWith("-")) {
        throw K9OGateConfigException(
            code = "K9O_INVALID_FIELD_VALUE",
            message = "orgSlug \"$slug\" does not match required format ^[a-z][a-z0-9-]{1,62}\$ (must not end with '-')",
            context = mapOf("field" to "orgSlug", "slug" to slug),
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
    val u = try { URI(base).resolve(location) } catch (e: Exception) { return null }
    if (u.scheme != "https" || u.userInfo != null || u.port != -1 || u.host == null) return null
    val to = parseApiHost(u.host) ?: return null
    if (to.region == null || to.orgSlug != from.orgSlug || to.family != from.family || to.apex != from.apex) return null
    return "https://${u.host}"
}

/**
 * Credential-free `HEAD` that does not follow redirects; returns the `Location` of a `308` answer, else null.
 * It is sent on a copy of [client] with its interceptors removed, so no Authorization or default header an
 * interceptor adds can reach the region-free address. Throws on a network failure.
 */
internal fun defaultProbe(client: OkHttpClient): suspend (String) -> String? = { url ->
    withContext(Dispatchers.IO) {
        val c = client.newBuilder().followRedirects(false).followSslRedirects(false).apply {
            interceptors().clear()
            networkInterceptors().clear()
        }.build()
        val req = Request.Builder().url(url).head().header("Accept", "*/*").build()
        c.newCall(req).execute().use { r -> if (r.code == 308) r.header("location") else null }
    }
}
