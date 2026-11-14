package dev.k9o.gate

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken

/**
 * Closed error taxonomy for K9OGate SDK operations.
 * Maps the gateway KError wire codes to Kotlin sealed class cases.
 *
 * A `K9O_*` code the SDK does not list is kept as [code] on [Unknown], not collapsed into a generic error.
 * [publicMessage] is a fixed sentence chosen by status class, the same in every language: show it to your users.
 * [message], [recommendation], [context] and [body] are for logs. [requestId] is the gateway's request id: log it.
 *
 * With `errors = "redacted"` on the client, [message] is the same text as [publicMessage], and [recommendation],
 * [context] and [body] are dropped. [status], [requestId], [publicMessage], [code] of a known error and
 * [retryAfterSec] are kept.
 */
sealed class GateError private constructor(fields: GateErrorFields) : Exception(fields.message, fields.cause) {
    /** The wire code as the gateway sent it (for example `K9O_INVALID_CREDENTIALS`), or null when there is none. */
    val code: String? = fields.code
    /** The HTTP status of the response, or null when no response was received. */
    val status: Int? = fields.status
    /** The gateway's recommendation text, or null. For logs. */
    val recommendation: String? = fields.recommendation
    /** Seconds the gateway asks the caller to wait before retrying, or null. Kept in both `errors` modes. */
    val retryAfterSec: Double? = fields.retryAfterSec
    /** The gateway's structured context for this error, or empty. For logs. */
    val context: Map<String, Any?> = fields.context
    /** The `x-k9o-request-id` response header, or null. Log it; it identifies the request for support. */
    val requestId: String? = fields.requestId
    /** The raw response body, or null. For logs. */
    val body: String? = fields.body
    /** A fixed sentence by status class, the same in every language. Safe to show to users. */
    val publicMessage: String = fields.publicMessage
    /**
     * Why the gateway ended the session (`K9O_SESSION_EXPIRED`): `inactive`, `max_length`, `revoked` or `replay`; null
     * for any other error. Kept in both `errors` modes. The user must sign in again.
     */
    val sessionEndReason: String? = fields.sessionEndReason

    /** Session token missing, expired, or revoked (HTTP 401). */
    class Unauthenticated internal constructor(fields: GateErrorFields) : GateError(fields)
    /** Caller lacks permission for the requested operation (HTTP 403). */
    class Forbidden internal constructor(fields: GateErrorFields) : GateError(fields)
    /** Email/password (or other credential) did not match (HTTP 401 INVALID_CREDENTIALS). */
    class InvalidCredentials internal constructor(fields: GateErrorFields) : GateError(fields)
    /** The step that finds the email's region is rate-limited (HTTP 429). Safe to retry. */
    class RateLimited internal constructor(val statusCode: Int, fields: GateErrorFields) : GateError(fields)
    /** The region that holds the email is unavailable (HTTP 503). Safe to retry after [retryAfterSec] or the hint in [body]. */
    class RegionUnavailable internal constructor(val statusCode: Int, fields: GateErrorFields) : GateError(fields)
    /** Transport-level failure — network unreachable, timeout, etc. */
    class Network internal constructor(fields: GateErrorFields) : GateError(fields)
    /** Gateway response could not be parsed into the expected shape. */
    class Decoding internal constructor(fields: GateErrorFields) : GateError(fields)
    /**
     * Any other non-2xx HTTP status not covered by the cases above. [code] is the wire code the gateway sent, kept as
     * it is when the SDK does not list it; in redacted mode it is null.
     */
    class Unknown internal constructor(val statusCode: Int, fields: GateErrorFields) : GateError(fields)
}

/**
 * The parts of a [GateError], decided once where the failure is seen, so the `errors` mode is applied in one place.
 * [message] is what [Exception.message] shows.
 */
internal class GateErrorFields(
    val message: String,
    val publicMessage: String,
    val cause: Throwable? = null,
    val status: Int? = null,
    val code: String? = null,
    val recommendation: String? = null,
    val retryAfterSec: Double? = null,
    val context: Map<String, Any?> = emptyMap(),
    val requestId: String? = null,
    val body: String? = null,
    val sessionEndReason: String? = null,
)

private val SESSION_END_REASONS = setOf("inactive", "max_length", "revoked", "replay")

// MARK: — Public message and default messages (keep these exact strings; the same in every language)

internal const val PUBLIC_AUTH = "You are not signed in or not allowed to do this."
internal const val PUBLIC_NOT_FOUND = "Not found."
internal const val PUBLIC_RATE_LIMITED = "Too many attempts. Try again later."
internal const val PUBLIC_SERVER = "Something went wrong. Try again later."
internal const val PUBLIC_NETWORK = "Could not reach the service."
internal const val PUBLIC_OTHER = "The request could not be completed."

/** The public sentence for an HTTP status: 401/403, 404, 429, 5xx, and anything else. */
internal fun publicMessageFor(status: Int?): String = when (status) {
    401, 403 -> PUBLIC_AUTH
    404 -> PUBLIC_NOT_FOUND
    429 -> PUBLIC_RATE_LIMITED
    in 500..599 -> PUBLIC_SERVER
    else -> PUBLIC_OTHER
}

private const val MSG_AUTH = "Session expired or not authenticated."
private const val MSG_FORBIDDEN = "Access forbidden for the current session."
private const val MSG_INVALID_CREDENTIALS = "Invalid credentials."
private const val MSG_RATE_LIMITED = "Too many requests. Try again shortly."
private const val MSG_REGION_UNAVAILABLE = "The region is unavailable. Try again shortly."

/** An [GateError.Unauthenticated] raised by the client itself, with no response: no refresh token to use. */
internal fun noSessionError(redacted: Boolean): GateError.Unauthenticated =
    GateError.Unauthenticated(GateErrorFields(message = if (redacted) PUBLIC_AUTH else MSG_AUTH, publicMessage = PUBLIC_AUTH))

internal fun networkError(cause: Throwable, redacted: Boolean): GateError.Network {
    val text = "Network error: ${cause.message}"
    return GateError.Network(GateErrorFields(message = if (redacted) PUBLIC_NETWORK else text, publicMessage = PUBLIC_NETWORK, cause = cause))
}

internal fun decodingError(cause: Throwable, redacted: Boolean): GateError.Decoding {
    val text = "Response decoding error: ${cause.message}"
    return GateError.Decoding(GateErrorFields(message = if (redacted) PUBLIC_OTHER else text, publicMessage = PUBLIC_OTHER, cause = cause))
}

// MARK: — Wire error envelope

// The gateway emits a KError at the ROOT of the error body:
// { code, message, context?, recommendation? } — NOT wrapped in { error: … }.
private val wireGson = Gson()
private val CONTEXT_TYPE = object : TypeToken<Map<String, Any?>>() {}.type

/** The request id the gateway sends on every response. */
internal const val REQUEST_ID_HEADER = "x-k9o-request-id"

internal fun gateErrorFromResponse(
    statusCode: Int,
    body: String?,
    requestId: String? = null,
    redacted: Boolean = false,
): GateError {
    val wire: JsonObject? = body?.let {
        try { wireGson.fromJson(it, JsonObject::class.java) } catch (_: JsonParseException) { null }
    }
    // Wire codes are platform-namespaced (K9O_*); the SDK matches on the code without the prefix.
    val code = wire?.stringField("code")
    val wireMessage = wire?.stringField("message")
    val recommendation = decodeRecommendation(wire?.get("recommendation"))
    val context = wire?.get("context")?.takeIf { it.isJsonObject }?.let { decodeContext(it) } ?: emptyMap()
    val publicMessage = publicMessageFor(statusCode)

    // The reason a session ended is one of four fixed words, so it is kept in both modes.
    val sessionEndReason = if (code?.removePrefix("K9O_") == "SESSION_EXPIRED") {
        wire.get("context")?.takeIf { it.isJsonObject }?.asJsonObject?.stringField("reason")?.takeIf { it in SESSION_END_REASONS }
    } else {
        null
    }

    // Known cases keep their code in both modes; the message is the fixed text, or the public sentence when redacted.
    fun fields(logMessage: String, known: Boolean) = GateErrorFields(
        message = if (redacted) publicMessage else logMessage,
        publicMessage = publicMessage,
        status = statusCode,
        code = if (redacted && !known) null else code,
        recommendation = if (redacted) null else recommendation.text,
        retryAfterSec = recommendation.retryAfterSec,
        context = if (redacted) emptyMap() else context,
        requestId = requestId,
        body = if (redacted) null else body,
        sessionEndReason = sessionEndReason,
    )

    return when (code?.removePrefix("K9O_")) {
        "UNAUTHENTICATED", "SESSION_EXPIRED", "MISSING_TOKEN" -> GateError.Unauthenticated(fields(MSG_AUTH, known = true))
        "FORBIDDEN" -> GateError.Forbidden(fields(MSG_FORBIDDEN, known = true))
        "INVALID_CREDENTIALS" -> GateError.InvalidCredentials(fields(MSG_INVALID_CREDENTIALS, known = true))
        "RATE_LIMITED" -> GateError.RateLimited(statusCode, fields(MSG_RATE_LIMITED, known = true))
        "GATE_REGION_UNAVAILABLE" -> GateError.RegionUnavailable(statusCode, fields(MSG_REGION_UNAVAILABLE, known = true))
        else -> GateError.Unknown(
            statusCode,
            fields(wireMessage ?: "Unexpected error (HTTP $statusCode): ${body ?: "<no body>"}", known = false),
        )
    }
}

private class DecodedRecommendation(val text: String?, val retryAfterSec: Double?)

/**
 * The wire `recommendation`: a string is kept; an object gives its `action`, else its `note`. `retryAfterSec` is the
 * object's number when it is finite and not negative. Anything else gives nothing.
 */
private fun decodeRecommendation(raw: JsonElement?): DecodedRecommendation {
    val none = DecodedRecommendation(null, null)
    if (raw == null || raw.isJsonNull) return none
    if (raw.isJsonPrimitive && raw.asJsonPrimitive.isString) return DecodedRecommendation(raw.asString, null)
    if (!raw.isJsonObject) return none
    val o = raw.asJsonObject
    val text = o.stringField("action") ?: o.stringField("note")
    val wait = o.get("retryAfterSec")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
    return DecodedRecommendation(text, wait?.takeIf { it.isFinite() && it >= 0 })
}

private fun decodeContext(element: JsonElement): Map<String, Any?> = try {
    wireGson.fromJson<Map<String, Any?>>(element, CONTEXT_TYPE) ?: emptyMap()
} catch (_: JsonParseException) {
    emptyMap()
}

private fun JsonObject.stringField(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
