package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_800_000_000L
private const val ISS = "https://api.example.test/rampart"
private const val PROJ = "proj_abc123"
private val B64 = java.util.Base64.getUrlEncoder().withoutPadding()

/** Answers by path, records every request, and can be told to fail the key feed. */
private class RouteTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var classifyStatus = 200
    var classifyBody = "{}"
    var jwksStatus = 200
    var jwksBody = """{"keys":[]}"""
    var jwksFail: Exception? = null

    override suspend fun send(request: HttpRequest): HttpResponse {
        requests += request
        return if (request.url.endsWith("/rampart/jwks")) {
            jwksFail?.let { throw it }
            HttpResponse(jwksStatus, jwksBody)
        } else HttpResponse(classifyStatus, classifyBody)
    }

    fun feedFetches() = requests.count { it.url.endsWith("/rampart/jwks") }
}

/** A signer standing in for the gateway. */
private class Signer(val kid: String) {
    val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val x: String = pair.public.encoded.let { B64.encodeToString(it.copyOfRange(it.size - 32, it.size)) }

    fun jwk() = RampartJwk(kid, "OKP", "Ed25519", x, "EdDSA", "sig")

    fun feedJson(issuer: String? = ISS): String =
        "{" + (if (issuer == null) "" else """"issuer":"$issuer",""") +
            """"keys":[{"crv":"Ed25519","x":"$x","kty":"OKP","kid":"$kid","alg":"EdDSA","use":"sig"}],"meta":{}}"""

    fun sign(headerJson: String, claimsJson: String): String {
        val signing = B64.encodeToString(headerJson.toByteArray()) + "." + B64.encodeToString(claimsJson.toByteArray())
        val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(signing.toByteArray(Charsets.US_ASCII)); sign() }
        return signing + "." + B64.encodeToString(sig)
    }
}

private fun sha(input: Any?): String =
    MessageDigest.getInstance("SHA-256").digest(Jcs.canonicalBytes(input)).joinToString("") { "%02x".format(it) }

private val INPUT = mapOf("text" to "hello", "n" to 1)

private fun header(kid: String, alg: String = "EdDSA", typ: String = "k9o.rampart.classify.v1") =
    """{"alg":"$alg","kid":"$kid","typ":"$typ"}"""

private fun claims(
    iss: String = ISS, project: String = PROJ, iat: Long = NOW - 5, exp: Long = NOW + 3600, sha: String = sha(INPUT),
    classification: String = "suspicious", risk: String = "6200", extra: String = "",
) = """{"iss":"$iss","iat":$iat,"exp":$exp,"input_sha256":"$sha","classification":"$classification","risk_bp":$risk,"confidence_bp":8100,""" +
    """"categories":["injection"],"project_id":"$project","calibration_version":"cal-7"$extra}"""

class RampartTest {
    private fun core(t: RouteTransport, slug: String? = "portal") =
        K9OCore("https://acme.api-de01.k9o.io", slug, accessToken = "tok_secret", transport = t)

    private fun opts(s: Signer) = RampartVerifyOptions(keys = RampartJwks(listOf(s.jwk()), ISS), projectId = PROJ, nowMillis = { NOW * 1000 })

    private fun good(s: Signer) = s.sign(header(s.kid), claims())

    private fun refused(reason: RampartReason, receipt: String, o: RampartVerifyOptions, input: Any? = INPUT, t: RouteTransport = RouteTransport()): RampartReceiptRefusal =
        runBlocking {
            val e = assertFailsWith<RampartReceiptRefusal> { core(t).rampart.verifyReceipt(receipt, input, o) }
            assertEquals(reason, e.reason, e.message)
            e
        }

    // ---- wire

    @Test
    fun `classify posts input and label and parses the verdict`() = runBlocking {
        val t = RouteTransport()
        t.classifyBody = """{"data":{"classification":"suspicious","riskScore":6200,"confidence":8100,"categories":["injection","x"],""" +
            """"calibrationVersion":"cal-7","classificationReceipt":"a.b.c","projectId":"proj_x","extra":1},"meta":{"requestId":"r1"}}"""
        val r = core(t).rampart.classify(INPUT, "support-chat")
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("https://acme.api-de01.k9o.io/@portal/rampart/classify", req.url)
        assertEquals("Bearer tok_secret", req.headers["authorization"])
        val body = JsonParser.parseString(req.body).asJsonObject
        assertEquals("hello", body.getAsJsonObject("input").get("text").asString)
        assertEquals("support-chat", body.get("label").asString)
        assertEquals(RampartVerdict("suspicious", 6200, 8100, listOf("injection", "x"), "cal-7", "a.b.c", "proj_x"), r.data)
        assertEquals("r1", r.meta["requestId"])
    }

    @Test
    fun `label is optional and fractional numbers are sent unchecked`() = runBlocking {
        val t = RouteTransport()
        t.classifyBody = """{"data":{"classification":"clear","riskScore":0,"confidence":9000,"categories":[],"calibrationVersion":"c","classificationReceipt":"a.b.c"},"meta":{}}"""
        val r = core(t).rampart.classify(mapOf("score" to 0.5))
        val body = JsonParser.parseString(t.requests.single().body).asJsonObject
        assertEquals(setOf("input"), body.keySet())
        assertTrue(!body.has("label"))
        assertEquals(0.5, body.getAsJsonObject("input").get("score").asDouble)
        assertNull(r.data.projectId)
    }

    @Test
    fun `argument checks happen before any request`() = runBlocking {
        val t = RouteTransport()
        var e = assertFailsWith<KError> { core(t, slug = null).rampart.classify(INPUT) }
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.code)
        for (label in listOf("", "x".repeat(129))) {
            e = assertFailsWith<KError> { core(t).rampart.classify(INPUT, label) }
            assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        }
        assertTrue(t.requests.isEmpty())
    }

    @Test
    fun `input too long is a typed 413 error and never a verdict`() = runBlocking {
        val t = RouteTransport()
        t.classifyStatus = 413
        t.classifyBody = """{"code":"K9O_RAMPART_INPUT_TOO_LONG","message":"too long","context":{"reason":"rampart_input_too_long"},"recommendation":null}"""
        val e = assertFailsWith<KError> { core(t).rampart.classify(INPUT) }
        assertEquals("K9O_RAMPART_INPUT_TOO_LONG", e.code)
        assertEquals(413, e.status)
    }

    @Test
    fun `classifier unavailable is a typed error and never a verdict`() = runBlocking {
        val t = RouteTransport()
        t.classifyStatus = 503
        t.classifyBody = """{"code":"K9O_RAMPART_CLASSIFIER_UNAVAILABLE","message":"no verdict","context":{},"recommendation":null}"""
        val e = assertFailsWith<KError> { core(t).rampart.classify(INPUT) }
        assertEquals("K9O_RAMPART_CLASSIFIER_UNAVAILABLE", e.code)
        assertEquals(503, e.status)
    }

    @Test
    fun `a malformed verdict is an internal error`() = runBlocking {
        val t = RouteTransport()
        t.classifyBody = """{"data":{"classification":"clear"},"meta":{}}"""
        assertEquals("K9O_INTERNAL_ERROR", assertFailsWith<KError> { core(t).rampart.classify(INPUT) }.code)
    }

    @Test
    fun `jwks is public and has no project prefix`() = runBlocking {
        val t = RouteTransport()
        val s = Signer("k1")
        t.jwksBody = s.feedJson()
        val feed = core(t).rampart.jwks()
        val req = t.requests.single()
        assertEquals("GET", req.method)
        assertEquals("https://acme.api-de01.k9o.io/rampart/jwks", req.url)
        assertNull(req.headers["authorization"])
        assertNull(req.headers["x-k9o-project-slug"])
        assertEquals(ISS, feed.issuer)
        assertEquals(listOf(s.jwk()), feed.keys)
    }

    @Test
    fun `jwks without an issuer or with a bad body`() = runBlocking {
        val t = RouteTransport()
        t.jwksBody = Signer("k1").feedJson(null)
        assertNull(core(t).rampart.jwks().issuer)
        t.jwksBody = """{"keys":[{"x":"a"}]}"""
        assertEquals("K9O_INTERNAL_ERROR", assertFailsWith<KError> { core(t).rampart.jwks() }.code)
        t.jwksBody = """{"nokeys":true}"""
        assertEquals("K9O_INTERNAL_ERROR", assertFailsWith<KError> { core(t).rampart.jwks() }.code)
        t.jwksStatus = 500
        t.jwksBody = """{"code":"K9O_INTERNAL_ERROR","message":"down","context":{}}"""
        assertEquals(500, assertFailsWith<KError> { core(t).rampart.jwks() }.status)
    }

    // ---- verifyReceipt

    @Test
    fun `a good receipt verifies from the classify result and reads the verdict from the claims`() = runBlocking {
        val s = Signer("k1")
        val t = RouteTransport()
        t.jwksBody = s.feedJson()
        val c = core(t)
        t.classifyBody = """{"data":{"classification":"clear","riskScore":1,"confidence":1,"categories":[],"calibrationVersion":"lies",""" +
            """"classificationReceipt":"${good(s)}","projectId":"$PROJ"},"meta":{}}"""
        val result = c.rampart.classify(INPUT)
        val v = c.rampart.verifyReceipt(result, INPUT, RampartVerifyOptions(nowMillis = { NOW * 1000 }))
        assertEquals("suspicious", v.classification) // the unauthenticated wire fields said "clear"
        assertEquals(6200, v.riskScore)
        assertEquals(8100, v.confidence)
        assertEquals(listOf("injection"), v.categories)
        assertEquals("cal-7", v.calibrationVersion)
        assertEquals(PROJ, v.projectId)
        assertEquals(ISS, v.issuer)
        assertEquals(sha(INPUT), v.inputSha256)
        assertEquals(NOW - 5, v.issuedAt)
        assertEquals(NOW + 3600, v.expiresAt)
        assertEquals("k1", v.kid)
        // the same receipt through the verdict itself and through a bare string with the project option
        c.rampart.verifyReceipt(result.data, INPUT, RampartVerifyOptions(nowMillis = { NOW * 1000 }))
        c.rampart.verifyReceipt(result.data.classificationReceipt, INPUT, RampartVerifyOptions(projectId = PROJ, nowMillis = { NOW * 1000 }))
        assertEquals(1, t.feedFetches(), "the feed is fetched once and kept")
    }

    @Test
    fun `the input may be written as a map, a list, a Gson element or a different key order`() = runBlocking {
        val s = Signer("k1")
        val receipt = good(s)
        val c = core(RouteTransport())
        c.rampart.verifyReceipt(receipt, linkedMapOf("n" to 1, "text" to "hello"), opts(s))
        c.rampart.verifyReceipt(receipt, JsonParser.parseString("""{"text":"hello","n":1.0}"""), opts(s))
        Unit
    }

    @Test
    fun `windows claim present absent invalid and unknown claims`() = runBlocking {
        val s = Signer("k1")
        val c = core(RouteTransport())
        fun v(extra: String) = runBlocking { c.rampart.verifyReceipt(s.sign(header(s.kid), claims(extra = extra)), INPUT, opts(s)) }
        assertEquals(1, v("").windows)
        assertEquals(3, v(",\"windows\":3").windows)
        assertEquals(16, v(",\"windows\":16.0").windows)
        assertEquals(1, v(",\"future_claim\":{\"a\":[1]}").windows)
        for (bad in listOf("0", "-1", "1.5", "\"2\"", "null", "true", "[1]")) {
            refused(RampartReason.MALFORMED, s.sign(header(s.kid), claims(extra = ",\"windows\":$bad")), opts(s))
        }
    }

    @Test
    fun `malformed receipts`() {
        val s = Signer("k1")
        val good = good(s)
        val parts = good.split(".")
        val o = opts(s)
        for (bad in listOf(
            "", "a.b", "a.b.c.d", "a..c", "!!.b.c", good.substring(0, good.length - 1) + "*",
            B64.encodeToString("[]".toByteArray()) + "." + parts[1] + "." + parts[2],            // header is not an object
            B64.encodeToString("{".toByteArray()) + "." + parts[1] + "." + parts[2],             // header is not JSON
            parts[0] + "." + B64.encodeToString("null".toByteArray()) + "." + parts[2],          // claims not an object
            B64.encodeToString(byteArrayOf(0x7b, 0xff.toByte(), 0x7d)) + "." + parts[1] + "." + parts[2], // not UTF-8
            "a.b.c",
        )) refused(RampartReason.MALFORMED, bad, o)
        // a header without kid, with crit, with b64 false
        refused(RampartReason.MALFORMED, s.sign("""{"alg":"EdDSA","typ":"k9o.rampart.classify.v1"}""", claims()), o)
        refused(RampartReason.MALFORMED, s.sign("""{"alg":"EdDSA","kid":"k1","typ":"k9o.rampart.classify.v1","crit":["x"]}""", claims()), o)
        refused(RampartReason.MALFORMED, s.sign("""{"alg":"EdDSA","kid":"k1","typ":"k9o.rampart.classify.v1","b64":false}""", claims()), o)
        // claims: missing or wrong-typed fields (each is signed, so only the claim check can refuse them)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims().replace("\"iat\":${NOW - 5}", "\"iat\":\"x\"")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims().replace("\"input_sha256\"", "\"x\"")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims(classification = "fine")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims(risk = "10001")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims(risk = "1.5")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims().replace("\"categories\":[\"injection\"]", "\"categories\":[1]")), o)
        refused(RampartReason.MALFORMED, s.sign(header("k1"), claims().replace("\"calibration_version\":\"cal-7\"", "\"calibration_version\":7")), o)
    }

    @Test
    fun `key unknown and key unavailable`() {
        val s = Signer("k1")
        val other = Signer("k2")
        refused(RampartReason.KEY_UNKNOWN, other.sign(header("k2"), claims()), opts(s))
        // a key that is not an Ed25519 OKP key
        val rsa = RampartJwks(listOf(RampartJwk("k1", "RSA", null, null)), ISS)
        refused(RampartReason.KEY_UNKNOWN, good(s), RampartVerifyOptions(keys = rsa, projectId = PROJ, nowMillis = { NOW * 1000 }))
        val short = RampartJwks(listOf(RampartJwk("k1", "OKP", "Ed25519", "AAAA")), ISS)
        refused(RampartReason.KEY_UNKNOWN, good(s), RampartVerifyOptions(keys = short, projectId = PROJ, nowMillis = { NOW * 1000 }))
        // the feed cannot be read: network failure and an error answer
        val down = RouteTransport().apply { jwksFail = java.io.IOException("offline") }
        refused(RampartReason.KEY_UNAVAILABLE, good(s), RampartVerifyOptions(projectId = PROJ), t = down)
        val err = RouteTransport().apply { jwksStatus = 500; jwksBody = """{"code":"K9O_INTERNAL_ERROR","message":"x","context":{}}""" }
        refused(RampartReason.KEY_UNAVAILABLE, good(s), RampartVerifyOptions(projectId = PROJ), t = err)
        val junk = RouteTransport().apply { jwksBody = "not json" }
        refused(RampartReason.KEY_UNAVAILABLE, good(s), RampartVerifyOptions(projectId = PROJ), t = junk)
    }

    @Test
    fun `an unknown kid refetches the feed once`() = runBlocking {
        val s1 = Signer("k1")
        val s2 = Signer("k2")
        val t = RouteTransport()
        t.jwksBody = s1.feedJson()
        val c = core(t)
        val o = RampartVerifyOptions(projectId = PROJ, nowMillis = { NOW * 1000 })
        c.rampart.verifyReceipt(good(s1), INPUT, o)
        assertEquals(1, t.feedFetches())
        // k2 appears in the feed later: one refetch finds it
        t.jwksBody = """{"issuer":"$ISS","keys":[${s1.jwk().let { """{"crv":"Ed25519","x":"${it.x}","kty":"OKP","kid":"k1"}""" }},{"crv":"Ed25519","x":"${s2.x}","kty":"OKP","kid":"k2"}]}"""
        c.rampart.verifyReceipt(s2.sign(header("k2"), claims()), INPUT, o)
        assertEquals(2, t.feedFetches())
        // a kid that is still unknown costs exactly one more fetch, then is refused
        val e = assertFailsWith<RampartReceiptRefusal> { c.rampart.verifyReceipt(Signer("k3").let { it.sign(header("k3"), claims()) }, INPUT, o) }
        assertEquals(RampartReason.KEY_UNKNOWN, e.reason)
        assertEquals(3, t.feedFetches())
        // and a kid already in the cache costs none
        c.rampart.verifyReceipt(good(s1), INPUT, o)
        assertEquals(3, t.feedFetches())
    }

    @Test
    fun `alg typ and signature`() {
        val s = Signer("k1")
        val o = opts(s)
        refused(RampartReason.ALG, s.sign(header("k1", alg = "RS256"), claims()), o)
        refused(RampartReason.ALG, s.sign(header("k1", alg = "none"), claims()), o)
        refused(RampartReason.TYP, s.sign(header("k1", typ = "JWT"), claims()), o)
        // altered claims under the original signature
        val parts = good(s).split(".")
        val altered = B64.encodeToString(claims(classification = "clear").toByteArray())
        refused(RampartReason.SIGNATURE, parts[0] + "." + altered + "." + parts[2], o)
        // signed by a different key under the same kid
        refused(RampartReason.SIGNATURE, Signer("k1").sign(header("k1"), claims()), o)
        // a short signature
        refused(RampartReason.SIGNATURE, parts[0] + "." + parts[1] + "." + B64.encodeToString(ByteArray(10)), o)
    }

    @Test
    fun `issuer rules`() {
        val s = Signer("k1")
        val at = { NOW * 1000 }
        // wrong issuer, from the feed and from the option
        refused(RampartReason.ISSUER, s.sign(header("k1"), claims(iss = "https://evil.test/rampart")), opts(s))
        refused(RampartReason.ISSUER, good(s), RampartVerifyOptions(keys = RampartJwks(listOf(s.jwk()), ISS), issuer = "https://other.test/rampart", projectId = PROJ, nowMillis = at))
        // no issuer anywhere is a refusal, never a pass
        refused(RampartReason.ISSUER, good(s), RampartVerifyOptions(keys = RampartJwks(listOf(s.jwk())), projectId = PROJ, nowMillis = at))
        // the option overrides and also stands in for a feed with no issuer
        runBlocking {
            val c = core(RouteTransport())
            c.rampart.verifyReceipt(good(s), INPUT, RampartVerifyOptions(keys = RampartJwks(listOf(s.jwk())), issuer = ISS, projectId = PROJ, nowMillis = at))
            c.rampart.verifyReceipt(
                s.sign(header("k1"), claims(iss = "https://x.test/rampart")), INPUT,
                RampartVerifyOptions(keys = RampartJwks(listOf(s.jwk()), ISS), issuer = "https://x.test/rampart", projectId = PROJ, nowMillis = at),
            )
        }
    }

    @Test
    fun `project rules`() {
        val s = Signer("k1")
        val at = { NOW * 1000 }
        val keys = RampartJwks(listOf(s.jwk()), ISS)
        refused(RampartReason.PROJECT, good(s), RampartVerifyOptions(keys = keys, projectId = "proj_other", nowMillis = at))
        refused(RampartReason.PROJECT, s.sign(header("k1"), claims(project = "proj_other")), opts(s))
        // a bare receipt with neither option nor carried id
        refused(RampartReason.PROJECT_UNKNOWN, good(s), RampartVerifyOptions(keys = keys, nowMillis = at))
        // a classify result with no projectId is the same
        runBlocking {
            val verdict = RampartVerdict("suspicious", 6200, 8100, listOf("injection"), "cal-7", good(s), null)
            val e = assertFailsWith<RampartReceiptRefusal> { core(RouteTransport()).rampart.verifyReceipt(verdict, INPUT, RampartVerifyOptions(keys = keys, nowMillis = at)) }
            assertEquals(RampartReason.PROJECT_UNKNOWN, e.reason)
            // a carried id that differs from the signed claim
            val wrong = verdict.copy(projectId = "proj_other")
            val e2 = assertFailsWith<RampartReceiptRefusal> { core(RouteTransport()).rampart.verifyReceipt(wrong, INPUT, RampartVerifyOptions(keys = keys, nowMillis = at)) }
            assertEquals(RampartReason.PROJECT, e2.reason)
            // the option wins over the carried id
            core(RouteTransport()).rampart.verifyReceipt(wrong, INPUT, RampartVerifyOptions(keys = keys, projectId = PROJ, nowMillis = at))
        }
    }

    @Test
    fun `time window with skew`() {
        val s = Signer("k1")
        val keys = RampartJwks(listOf(s.jwk()), ISS)
        fun at(now: Long) = RampartVerifyOptions(keys = keys, projectId = PROJ, nowMillis = { now * 1000 })
        val r = s.sign(header("k1"), claims(iat = NOW, exp = NOW + 100))
        runBlocking {
            val c = core(RouteTransport())
            c.rampart.verifyReceipt(r, INPUT, at(NOW + 160)) // exp + 60 still passes
            c.rampart.verifyReceipt(r, INPUT, at(NOW - 60))  // iat - 60 still passes
        }
        refused(RampartReason.EXPIRED, r, at(NOW + 161))
        refused(RampartReason.NOT_YET_VALID, r, at(NOW - 61))
        val wide = RampartVerifyOptions(keys = keys, projectId = PROJ, clockSkewSeconds = 600, nowMillis = { (NOW + 700) * 1000 })
        runBlocking { core(RouteTransport()).rampart.verifyReceipt(r, INPUT, wide) }
    }

    @Test
    fun `input hash`() {
        val s = Signer("k1")
        val o = opts(s)
        refused(RampartReason.INPUT_HASH, good(s), o, input = mapOf("text" to "hello", "n" to 2))
        refused(RampartReason.INPUT_HASH, good(s), o, input = mapOf("text" to "hello"))
        // an input the profile cannot express is input_hash, not a crash
        refused(RampartReason.INPUT_HASH, good(s), o, input = mapOf("text" to "hello", "n" to 1.5))
        refused(RampartReason.INPUT_HASH, good(s), o, input = mapOf("text" to "hello", "n" to Double.NaN))
        refused(RampartReason.INPUT_HASH, good(s), o, input = mapOf("big" to 9007199254740992L))
        refused(RampartReason.INPUT_HASH, good(s), o, input = Any())
    }

    @Test
    fun `the reason set is closed`() {
        assertEquals(
            listOf("malformed", "key_unavailable", "key_unknown", "alg", "typ", "signature", "issuer", "project", "project_unknown", "expired", "not_yet_valid", "input_hash"),
            RampartReason.values().map { it.wire },
        )
    }
}
