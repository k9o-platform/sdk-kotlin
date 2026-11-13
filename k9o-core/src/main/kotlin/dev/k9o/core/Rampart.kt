package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** The verdict and its receipt, as the gateway sent them. Read the verdict from [Rampart.verifyReceipt] when you need it authenticated: these fields are not. */
data class RampartVerdict(
    /** `clear`, `suspicious` or `malicious`. */
    val classification: String,
    /** Risk in basis points, an integer 0 to 10000. */
    val riskScore: Int,
    /** Confidence in basis points, an integer 0 to 10000. */
    val confidence: Int,
    val categories: List<String>,
    val calibrationVersion: String,
    /** The signed receipt (a compact JWS), exactly as received. */
    val classificationReceipt: String,
    /** The project id (`proj_...`) the receipt names, when the gateway reports it; [Rampart.verifyReceipt] compares it to the signed claim. */
    val projectId: String? = null,
)

/** One public key of the feed (an Ed25519 JWK). */
data class RampartJwk(
    val kid: String,
    val kty: String? = null,
    val crv: String? = null,
    val x: String? = null,
    val alg: String? = null,
    val use: String? = null,
)

/** The key feed: the keys and the issuer receipts carry (`<issuer>/rampart`). [issuer] is null when the gateway does not advertise one. */
data class RampartJwks(val keys: List<RampartJwk>, val issuer: String? = null)

/** Why a receipt was refused. The set is closed. */
enum class RampartReason(val wire: String) {
    MALFORMED("malformed"),
    KEY_UNAVAILABLE("key_unavailable"),
    KEY_UNKNOWN("key_unknown"),
    ALG("alg"),
    TYP("typ"),
    SIGNATURE("signature"),
    ISSUER("issuer"),
    PROJECT("project"),
    PROJECT_UNKNOWN("project_unknown"),
    EXPIRED("expired"),
    NOT_YET_VALID("not_yet_valid"),
    INPUT_HASH("input_hash"),
}

/** Thrown by [Rampart.verifyReceipt] for every refusal. There is no partial verdict: nothing of the receipt is returned. */
class RampartReceiptRefusal(val reason: RampartReason, val detail: String, cause: Throwable? = null) :
    Exception("rampart receipt refused (${reason.wire}): $detail", cause)

/** Options of [Rampart.verifyReceipt]; every field is optional. */
class RampartVerifyOptions(
    /** A key set you already fetched ([Rampart.jwks] also supplies the issuer). Without it the client fetches the feed itself. */
    val keys: RampartJwks? = null,
    /** The issuer the receipt must carry. Default: the issuer the key feed advertises. When neither exists the receipt is refused (`issuer`). */
    val issuer: String? = null,
    /**
     * The project id the receipt must name. Default: the `projectId` of the classify result passed in place of a
     * receipt string. A bare receipt string with neither is refused (`project_unknown`); a different id is refused (`project`).
     */
    val projectId: String? = null,
    /** Seconds of clock difference tolerated at both ends of the window. Default 60. */
    val clockSkewSeconds: Long = 60,
    /** The clock in epoch milliseconds, for tests. Default: the system clock. */
    val nowMillis: (() -> Long)? = null,
)

/** The claims of a receipt that passed every check. */
data class RampartVerified(
    val classification: String,
    val riskScore: Int,
    val confidence: Int,
    val categories: List<String>,
    val calibrationVersion: String,
    val projectId: String,
    /** Lowercase hex SHA-256 of the canonical input. */
    val inputSha256: String,
    val issuer: String,
    /** Seconds since the epoch. */
    val issuedAt: Long,
    val expiresAt: Long,
    val kid: String,
    /** How many windows of the input were scored (1 when it fits one window). A receipt without the claim reads as 1. */
    val windows: Int = 1,
)

/**
 * Rampart: hosted classification of content, with a signed receipt. `core.rampart`.
 *
 * Rampart is advisory. A verdict is information; the client never blocks anything and your code decides what to do.
 * While the classifier is unavailable [classify] throws [KError] 503 `K9O_RAMPART_CLASSIFIER_UNAVAILABLE`: you get no
 * verdict, never a default "clear".
 *
 * Wire: [classify] is `POST /@<projectSlug>/rampart/classify`; [jwks] is `GET /rampart/jwks` (public, no project
 * prefix, no credential sent).
 *
 * A receipt is an EdDSA JWS. [verifyReceipt] accepts it only when, in this order: the key named by the header `kid` is in
 * the key feed, `alg` is `EdDSA`, `typ` is `k9o.rampart.classify.v1`, the signature verifies, the issuer and project
 * match, the receipt is inside its time window (plus a clock skew), and its `input_sha256` equals the SHA-256 of the
 * canonical form of the exact input you pass (see [Jcs]). Signatures are checked by an in-house RFC 8032 verifier, not
 * the platform provider, so it works on every JVM and Android release.
 */
class Rampart internal constructor(private val http: HttpCore) {
    private class Feed(val keys: Map<String, RampartJwk>, val issuer: String?)

    private val feedLock = Mutex()
    private var cached: Feed? = null

    /**
     * Classify [input] (a JSON object) with an optional [label] (1 to 128 characters) naming the context. The answer holds
     * the verdict fields and the receipt as received; the verdict is not checked until you call [verifyReceipt]. Needs
     * `projectSlug` on the client. The input is sent as given: the server answers a typed 400 for a value it cannot
     * canonicalise (a fractional number, an integer above 2^53-1).
     *
     * A long input is scored in overlapping windows and the highest injection score decides. Throws [KError] 413
     * `K9O_RAMPART_INPUT_TOO_LONG` (past 16 windows, about 6,000 tokens; `context.reason` is `rampart_input_too_long`; no
     * verdict), 503 `K9O_RAMPART_CLASSIFIER_UNAVAILABLE` (the classifier could not answer; no verdict), 403/404 for a caller who is not a project member, `K9O_MISSING_PROJECT_CONTEXT` before any
     * request when no project is bound, and `K9O_INVALID_FIELD_VALUE` for a bad label.
     */
    suspend fun classify(input: JsonObject, label: String? = null): Envelope<RampartVerdict> {
        val slug = http.projectSlug
        if (slug.isNullOrEmpty()) {
            throw KError(
                code = "K9O_MISSING_PROJECT_CONTEXT",
                message = "rampart operations require projectSlug bound on the client",
                recommendation = "Construct K9OCore with projectSlug.",
            )
        }
        if (label != null && (label.isEmpty() || label.length > 128)) {
            throw KError(
                code = "K9O_INVALID_FIELD_VALUE",
                message = "label must be 1 to 128 characters",
                context = mapOf("field" to "label"),
            )
        }
        val body = JsonObject()
        body.add("input", input)
        if (label != null) body.addProperty("label", label)
        val env = http.post("/@$slug/rampart/classify", body)
        return Envelope(verdict(env.data), env.meta)
    }

    /** As [classify] for an input held in maps, lists, strings, numbers and booleans. */
    suspend fun classify(input: Map<String, Any?>, label: String? = null): Envelope<RampartVerdict> =
        classify(Gson().toJsonTree(input).asJsonObject, label)

    /**
     * The public keys that verify receipts and the issuer they carry, one key per `kid`. Public, so no credential is
     * sent. The feed is also kept for [verifyReceipt].
     */
    suspend fun jwks(): RampartJwks = feedLock.withLock { fetchFeed().second }

    /** Verify the receipt of a [classify] answer; the answer's `projectId` is the expected project unless [RampartVerifyOptions.projectId] says otherwise. */
    suspend fun verifyReceipt(result: Envelope<RampartVerdict>, input: Any?, options: RampartVerifyOptions = RampartVerifyOptions()): RampartVerified =
        verify(result.data.classificationReceipt, result.data.projectId, input, options)

    /** As above for the [RampartVerdict] itself. */
    suspend fun verifyReceipt(result: RampartVerdict, input: Any?, options: RampartVerifyOptions = RampartVerifyOptions()): RampartVerified =
        verify(result.classificationReceipt, result.projectId, input, options)

    /** Verify a bare receipt string; pass [RampartVerifyOptions.projectId] or it is refused as `project_unknown`. */
    suspend fun verifyReceipt(receipt: String, input: Any?, options: RampartVerifyOptions = RampartVerifyOptions()): RampartVerified =
        verify(receipt, null, input, options)

    // ------------------------------------------------------------------ feed

    private suspend fun fetchFeed(): Pair<Feed, RampartJwks> {
        val body = http.getPublicObject("/rampart/jwks")
        val keysEl = body.get("keys")
        if (keysEl == null || !keysEl.isJsonArray) throw feedError()
        val keys = ArrayList<RampartJwk>()
        for (k in keysEl.asJsonArray) {
            if (!k.isJsonObject) throw feedError()
            val o = k.asJsonObject
            val kid = o.str("kid") ?: throw feedError()
            keys.add(RampartJwk(kid, o.str("kty"), o.str("crv"), o.str("x"), o.str("alg"), o.str("use")))
        }
        val issuer = body.str("issuer")?.takeIf { it.isNotEmpty() }
        val jwks = RampartJwks(keys, issuer)
        val feed = Feed(keys.associateBy { it.kid }, issuer)
        cached = feed
        return feed to jwks
    }

    private fun feedError() =
        KError(code = "K9O_INTERNAL_ERROR", message = "The Rampart key feed is malformed: expected { keys: [...] }")

    private suspend fun keyFor(kid: String, injected: RampartJwks?): Pair<RampartJwk, String?> {
        if (injected != null) {
            val k = injected.keys.firstOrNull { it.kid == kid }
                ?: throw RampartReceiptRefusal(RampartReason.KEY_UNKNOWN, "no key with the receipt's kid in the key set you passed")
            return k to injected.issuer
        }
        val feed: Feed
        val k: RampartJwk?
        try {
            feed = feedLock.withLock {
                var f = cached
                var fresh = false
                if (f == null) { f = fetchFeed().first; fresh = true }
                if (f.keys[kid] == null && !fresh) f = fetchFeed().first // one refetch on an unknown kid
                f
            }
            k = feed.keys[kid]
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            throw RampartReceiptRefusal(RampartReason.KEY_UNAVAILABLE, "the key feed could not be read", e)
        }
        if (k == null) throw RampartReceiptRefusal(RampartReason.KEY_UNKNOWN, "no key with the receipt's kid in the key feed")
        return k to feed.issuer
    }

    // ---------------------------------------------------------------- verify

    private suspend fun verify(receipt: String, carriedProjectId: String?, input: Any?, o: RampartVerifyOptions): RampartVerified {
        val parts = receipt.split(".")
        if (parts.size != 3 || parts.any { it.isEmpty() }) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "a receipt has three dot-separated parts")
        }
        val (h, p, s) = parts
        val header = b64urlJson(h, "header")
        val claims = b64urlJson(p, "payload")
        val signature = Base64Url.decode(s) ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "a receipt part is not base64url")
        if (header.containsKey("crit") || header["b64"] == false) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "the header carries unsupported critical parameters")
        }

        // 1. The key is chosen by the header kid, from the feed; a key inside the receipt is never used.
        val kid = header["kid"] as? String ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "the header has no kid")
        val (jwk, feedIssuer) = keyFor(kid, o.keys)
        // 2. and 3. Algorithm and type.
        if (header["alg"] != "EdDSA") throw RampartReceiptRefusal(RampartReason.ALG, "the algorithm is not EdDSA")
        if (header["typ"] != RECEIPT_TYP) throw RampartReceiptRefusal(RampartReason.TYP, "the type is not $RECEIPT_TYP")
        // Signature, before any claim is trusted.
        val raw = (if (jwk.kty == "OKP" && jwk.crv == "Ed25519") jwk.x?.let { Base64Url.decode(it) } else null)
        if (raw == null || raw.size != 32) {
            throw RampartReceiptRefusal(RampartReason.KEY_UNKNOWN, "the key named by the receipt is not a usable Ed25519 key")
        }
        if (!Ed25519.verify(raw, "$h.$p".toByteArray(Charsets.US_ASCII), signature)) {
            throw RampartReceiptRefusal(RampartReason.SIGNATURE, "the signature does not verify")
        }

        // 4. Issuer, project, time window.
        val issuer = o.issuer ?: feedIssuer
            ?: throw RampartReceiptRefusal(RampartReason.ISSUER, "no issuer is advertised: the key feed carries none and no issuer option was given")
        if (claims["iss"] != issuer) throw RampartReceiptRefusal(RampartReason.ISSUER, "the issuer is not the expected one")
        val expectedProject = o.projectId ?: carriedProjectId?.takeIf { it.isNotEmpty() }
            ?: throw RampartReceiptRefusal(RampartReason.PROJECT_UNKNOWN, "no project id to compare: pass the classify result or options.projectId")
        if (claims["project_id"] != expectedProject) throw RampartReceiptRefusal(RampartReason.PROJECT, "the receipt names a different project")
        val iat = claims["iat"] as? Number
        val exp = claims["exp"] as? Number
        if (iat == null || exp == null || !iat.toDouble().isFinite() || !exp.toDouble().isFinite()) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "iat and exp must be numbers")
        }
        val nowSec = (o.nowMillis?.invoke() ?: System.currentTimeMillis()) / 1000.0
        if (nowSec > exp.toDouble() + o.clockSkewSeconds) throw RampartReceiptRefusal(RampartReason.EXPIRED, "the receipt has expired")
        if (nowSec < iat.toDouble() - o.clockSkewSeconds) throw RampartReceiptRefusal(RampartReason.NOT_YET_VALID, "the receipt was issued in the future")

        // 5. The receipt must attest this exact input.
        val digest = try {
            hex(MessageDigest.getInstance("SHA-256").digest(Jcs.canonicalBytes(input)))
        } catch (e: IllegalArgumentException) {
            throw RampartReceiptRefusal(RampartReason.INPUT_HASH, "the input cannot be canonicalised: ${e.message}")
        }
        val attested = claims["input_sha256"] as? String ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "input_sha256 is missing")
        if (!MessageDigest.isEqual(attested.toByteArray(Charsets.UTF_8), digest.toByteArray(Charsets.UTF_8))) {
            throw RampartReceiptRefusal(RampartReason.INPUT_HASH, "the receipt does not attest this input")
        }

        // 6. The verdict comes from the verified claims.
        val c = claims["classification"]
        if (c != "clear" && c != "suspicious" && c != "malicious") throw RampartReceiptRefusal(RampartReason.MALFORMED, "unknown classification")
        val risk = basisPoints(claims["risk_bp"])
        val conf = basisPoints(claims["confidence_bp"])
        if (risk == null || conf == null) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "risk_bp and confidence_bp must be integers 0 to 10000")
        }
        val cats = claims["categories"]
        if (cats !is List<*> || !cats.all { it is String }) throw RampartReceiptRefusal(RampartReason.MALFORMED, "categories must be a list of strings")
        val cal = claims["calibration_version"] as? String ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "calibration_version is missing")
        // `windows` is how many windows were scored; other claims the SDK does not know are ignored.
        val wc = claims["windows"]
        val windows = when {
            !claims.containsKey("windows") -> 1
            wc == null -> null
            wc is Long -> if (wc in 1..Int.MAX_VALUE.toLong()) wc.toInt() else null
            wc is Double -> if (wc == Math.floor(wc) && wc >= 1 && wc <= Int.MAX_VALUE.toDouble()) wc.toInt() else null
            else -> null
        } ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "windows must be a positive integer")
        @Suppress("UNCHECKED_CAST")
        return RampartVerified(
            classification = c as String,
            riskScore = risk,
            confidence = conf,
            categories = (cats as List<String>).toList(),
            calibrationVersion = cal,
            projectId = expectedProject,
            inputSha256 = digest,
            issuer = issuer,
            issuedAt = iat.toLong(),
            expiresAt = exp.toLong(),
            kid = kid,
            windows = windows,
        )
    }

    private fun basisPoints(v: Any?): Int? {
        val d = when (v) {
            is Long -> v.toDouble()
            is Double -> v
            else -> return null
        }
        return if (d == Math.floor(d) && d >= 0 && d <= 10000) d.toInt() else null
    }

    private fun b64urlJson(part: String, what: String): Map<String, Any?> {
        val bytes = Base64Url.decode(part) ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "a receipt part is not base64url")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "the receipt $what is not JSON")
        }
        val v = try {
            StrictJson.parse(text)
        } catch (e: StrictJson.SyntaxError) {
            throw RampartReceiptRefusal(RampartReason.MALFORMED, "the receipt $what is not JSON")
        }
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw RampartReceiptRefusal(RampartReason.MALFORMED, "the receipt $what is not an object")
    }

    private fun verdict(d: JsonObject): RampartVerdict {
        fun bad(): Nothing = throw KError(code = "K9O_INTERNAL_ERROR", message = "Malformed classify answer: expected the verdict fields and classificationReceipt", status = 200)
        val cats = d.get("categories")
        if (cats == null || !cats.isJsonArray) bad()
        return RampartVerdict(
            classification = d.str("classification") ?: bad(),
            riskScore = d.int("riskScore") ?: bad(),
            confidence = d.int("confidence") ?: bad(),
            categories = (cats as JsonArray).map { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else bad() },
            calibrationVersion = d.str("calibrationVersion") ?: bad(),
            classificationReceipt = d.str("classificationReceipt") ?: bad(),
            projectId = d.str("projectId"),
        )
    }

    private fun JsonObject.str(name: String): String? {
        val e: JsonElement = get(name) ?: return null
        return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else null
    }

    private fun JsonObject.int(name: String): Int? {
        val e: JsonElement = get(name) ?: return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isNumber) return null
        val d = e.asDouble
        return if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) <= Int.MAX_VALUE) d.toInt() else null
    }

    private fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(digits[(x.toInt() shr 4) and 15]).append(digits[x.toInt() and 15])
        return sb.toString()
    }

    companion object {
        /** The `typ` a classification receipt must carry. */
        const val RECEIPT_TYP: String = "k9o.rampart.classify.v1"
    }
}

/** Strict base64url without padding, as JWS uses it; null when [s] is not valid. Written here because `java.util.Base64` needs Android 8. */
internal object Base64Url {
    private val REV = IntArray(128) { -1 }.also { t ->
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        for (i in alphabet.indices) t[alphabet[i].code] = i
    }

    fun decode(s: String): ByteArray? {
        if (s.length % 4 == 1) return null
        var acc = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream(s.length * 3 / 4)
        for (ch in s) {
            val v = if (ch.code < 128) REV[ch.code] else -1
            if (v < 0) return null
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc shr bits) and 0xff)
                acc = acc and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
