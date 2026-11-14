package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Cyrillic and Greek look-alikes of the marker letters, mapped to the lowercase Latin letter (comparison text only).
 * Equals the shared confusables table, version 2; a test guards drift. This is not general homoglyph detection.
 */
internal val MOAT_CONFUSABLES: Map<Int, String> = mapOf(
    0x0430 to "a", 0x0410 to "a", 0x0441 to "c", 0x0421 to "c",
    0x0435 to "e", 0x0415 to "e", 0x0456 to "i", 0x0406 to "i",
    0x043E to "o", 0x041E to "o", 0x0440 to "p", 0x0420 to "p",
    0x0455 to "s", 0x0405 to "s", 0x0443 to "y", 0x0423 to "y",
    0x0501 to "d", 0x04CF to "l", 0x04C0 to "l", 0x0422 to "t",
    0x041C to "m", 0x041D to "h", 0x0445 to "x", 0x0425 to "x",
    0x0391 to "a", 0x03B1 to "a", 0x0395 to "e", 0x03B5 to "e",
    0x0399 to "i", 0x03B9 to "i", 0x039F to "o", 0x03BF to "o",
    0x03A1 to "p", 0x03C1 to "p", 0x03A4 to "t", 0x03C4 to "t",
    0x03A5 to "y", 0x03C5 to "u", 0x03BD to "v", 0x039C to "m",
    0x039D to "n", 0x03A7 to "x", 0x03C7 to "x", 0x03F2 to "c",
    0x03F9 to "c", 0x0397 to "h", 0x0442 to "t",
)

/** Why Moat refused. The set is closed. */
enum class MoatReason(val wire: String) {
    ROLE_MARKER("role_marker"),
    INPUT_SCHEMA("input_schema"),
    OUTPUT_SCHEMA("output_schema"),
    PII("pii"),
}

/** Thrown when input or output is refused. [detail] is short and never repeats the refused content. */
class MoatRefusal(val reason: MoatReason, val detail: String) : Exception("moat refused (${reason.wire}): $detail")

/** Thrown by the [Moat] constructor for an empty prompt, a keyword outside the supported subset or a malformed schema. */
class MoatConfigError(message: String) : IllegalArgumentException(message)

/** One chat message. */
data class MoatMessage(val role: String, val content: String)

/**
 * Moat: a local guard around one model call. It runs in your process, makes no network call and needs no account.
 *
 * What it does:
 * 1. Pins the system prompt. [messages] builds the message list itself: your prompt is always the one `system`
 *    message and the untrusted input is always a `user` message, never a `system` one.
 * 2. Refuses untrusted input that carries a role or system marker (a refusal, not a sanitiser).
 * 3. Enforces a declared input schema and a declared output schema.
 * 4. Filters the output for personal data through a function you pass (optional).
 *
 * The pin is structural. It stops injected text from becoming the system prompt; it does not stop that text from
 * reaching the model, and the model may still follow it. Moat is one layer, so keep treating the model's answer as
 * untrusted and check it with [checkOutput].
 *
 * Schemas use a small JSON Schema subset given as maps and lists: `type` (object, array, string, number, integer,
 * boolean, null, or a list of them), `properties`, `required`, `additionalProperties` (true or false), `items` (one
 * schema), `enum`, `const`, `minLength`, `maxLength`, `pattern` (searched, not anchored), `minimum`, `maximum`,
 * `minItems`, `maxItems`. A keyword outside the subset throws [MoatConfigError] at construction, so a typo never
 * switches a check off.
 *
 * @param prompt the system prompt, pinned
 * @param inputSchema schema the untrusted input must satisfy before it is placed in a message
 * @param outputSchema schema the model output must satisfy
 * @param pii looks at the output as text and returns null or an empty string when it is clean, else a short description
 *   of what was found (never the matched text). Without it there is no PII step. A filter that throws refuses the output.
 */
class Moat(
    prompt: String,
    inputSchema: Map<String, Any?>? = null,
    outputSchema: Map<String, Any?>? = null,
    private val pii: ((String) -> String?)? = null,
) {
    private val prompt: String
    private val inputSchema: Map<String, Any?>?
    private val outputSchema: Map<String, Any?>?
    private val patterns = HashMap<String, Pattern>()

    init {
        if (prompt.isEmpty()) throw MoatConfigError("prompt must be a non-empty string")
        this.prompt = prompt
        this.inputSchema = inputSchema
        this.outputSchema = outputSchema
        if (inputSchema != null) checkSchema(inputSchema, "inputSchema")
        if (outputSchema != null) checkSchema(outputSchema, "outputSchema")
    }

    /**
     * The message list for one model call: the pinned prompt as `system`, then the untrusted input as text in a `user`
     * message (a string as it is, anything else as canonical JSON). [untrusted] may be a string, number, boolean, null,
     * a map, a list or array of those, or a Gson element. Throws [MoatRefusal] (`input_schema` or `role_marker`).
     */
    fun messages(untrusted: Any?): List<MoatMessage> {
        val value = toNative(untrusted, 0)
        val schema = inputSchema
        if (schema != null) {
            val v = violation(value, schema, "$")
            if (v != null) throw MoatRefusal(MoatReason.INPUT_SCHEMA, v)
        }
        val text: String = if (value is String) value else try {
            Jcs.canonicalize(value)
        } catch (e: IllegalArgumentException) {
            // The receipt profile is integers only; the model still gets fractional numbers as plain compact JSON.
            compactJson(value)
        }
        val found = markerIn(text) ?: findMarker(value, 0)
        if (found != null) throw MoatRefusal(MoatReason.ROLE_MARKER, found)
        return listOf(MoatMessage("system", prompt), MoatMessage("user", text))
    }

    /**
     * Check what the model returned. With an output schema whose type is not exactly `string`, the text is parsed as JSON
     * first (a parse failure is `output_schema`, unless the schema also allows `string`) and the parsed value is returned;
     * otherwise the text is returned as it is. Then the PII filter, if any, sees the output as text (a parsed value is
     * written back as compact JSON, so a JSON escape cannot hide what the filter looks for). Returns the validated value:
     * `String`, `Boolean`, `null`, `Long`/`Double` numbers, `List`, or `Map` in input order. Throws [MoatRefusal]
     * (`output_schema` or `pii`).
     */
    fun checkOutput(modelOutput: String): Any? {
        var value: Any? = modelOutput
        val schema = outputSchema
        if (schema != null) {
            val types = typeList(schema["type"])
            val stringOnly = types.size == 1 && types[0] == "string"
            if (!stringOnly) {
                try {
                    value = StrictJson.parse(modelOutput)
                } catch (e: StrictJson.SyntaxError) {
                    if ("string" !in types) throw MoatRefusal(MoatReason.OUTPUT_SCHEMA, "the output is not valid JSON")
                }
            }
            val v = violation(value, schema, "$")
            if (v != null) throw MoatRefusal(MoatReason.OUTPUT_SCHEMA, v)
        }
        val filter = pii
        if (filter != null) {
            val text = if (value is String) value else compactJson(value)
            val found = try {
                filter(text)
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                throw MoatRefusal(MoatReason.PII, "the PII filter failed, so the output is not passed")
            }
            if (!found.isNullOrEmpty()) throw MoatRefusal(MoatReason.PII, found)
        }
        return value
    }

    /** Lowercase hex SHA-256 of the pinned prompt, for example to pass to Rampart as input. */
    fun promptSha256(): String {
        val d = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray(Charsets.UTF_8))
        val digits = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (x in d) sb.append(digits[(x.toInt() shr 4) and 15]).append(digits[x.toInt() and 15])
        return sb.toString()
    }

    // ----------------------------------------------------------- role markers

    private fun findMarker(v: Any?, depth: Int): String? {
        if (depth > MAX_MARKER_DEPTH) return "the input is nested too deeply"
        when (v) {
            is String -> {
                markerIn(v)?.let { return it }
                val t = v.trim()
                if (t.startsWith("{") || t.startsWith("[")) {
                    val inner = try { StrictJson.parse(v) } catch (e: StrictJson.SyntaxError) { null }
                    if (inner != null) findMarker(inner, depth + 1)?.let { return it }
                }
            }
            is Map<*, *> -> for ((k, x) in v) {
                val key = k as String
                if (normalise(key).trim() == "role" && x is String && normalise(x).trim() in BAD_ROLES) {
                    return "an object has a role key set to a privileged role"
                }
                findMarker(key, depth + 1)?.let { return it }
                findMarker(x, depth + 1)?.let { return it }
            }
            is List<*> -> for (e in v) findMarker(e, depth + 1)?.let { return it }
        }
        return null
    }

    private fun markerIn(text: String): String? {
        val n = normalise(text)
        for (line in n.split('\r', '\n', 0x85.toChar(), 0x2028.toChar(), 0x2029.toChar())) {
            if (LINE_ROLE.matcher(line).find()) return "a line starts with a role label"
        }
        if (HEADING.matcher(n).find()) return "the text contains a system heading"
        val flat = WHITESPACE.matcher(n).replaceAll(" ")
        for (m in TOKEN_MARKERS) if (flat.contains(m)) return "the text contains a chat-template marker"
        if (ROLE_JSON.matcher(flat).find()) return "the text contains a role key set to a privileged role"
        return null
    }

    // ---------------------------------------------------------------- schemas

    private fun typeList(t: Any?): List<String> = when (t) {
        null -> emptyList()
        is String -> listOf(t)
        is List<*> -> t.map { it as String }
        else -> emptyList()
    }

    private fun checkSchema(schema: Any?, path: String) {
        if (schema !is Map<*, *>) throw MoatConfigError("$path: a schema must be an object")
        for ((k, v) in schema) {
            if (k !is String || k !in KEYWORDS) throw MoatConfigError("$path: unsupported schema keyword \"$k\"")
            when (k) {
                "type" -> {
                    val list = if (v is List<*>) v else listOf(v)
                    if (list.isEmpty() || !list.all { it is String && it in TYPES }) {
                        throw MoatConfigError("$path: type must be one of ${TYPES.joinToString(", ")} or a list of them")
                    }
                }
                "properties" -> {
                    if (v !is Map<*, *>) throw MoatConfigError("$path: properties must be an object of schemas")
                    for ((pk, pv) in v) checkSchema(pv, "$path.$pk")
                }
                "required" -> if (v !is List<*> || !v.all { it is String }) throw MoatConfigError("$path: required must be a list of property names")
                "additionalProperties" -> if (v !is Boolean) throw MoatConfigError("$path: additionalProperties must be true or false")
                "items" -> checkSchema(v, "$path[]")
                "enum" -> if (v !is List<*> || v.isEmpty()) throw MoatConfigError("$path: enum must be a non-empty list")
                "const" -> {}
                "minLength", "maxLength", "minItems", "maxItems" -> {
                    val d = (v as? Number)?.let { decimal(it) }
                    if (d == null || d.signum() < 0 || d.stripTrailingZeros().scale() > 0) {
                        throw MoatConfigError("$path: $k must be a non-negative integer")
                    }
                }
                "minimum", "maximum" -> if (v !is Number || decimal(v) == null) throw MoatConfigError("$path: $k must be a finite number")
                "pattern" -> {
                    if (v !is String) throw MoatConfigError("$path: pattern must be a string")
                    try {
                        patterns[v] = Pattern.compile(v)
                    } catch (e: PatternSyntaxException) {
                        throw MoatConfigError("$path: pattern is not a valid regular expression")
                    }
                }
            }
        }
    }

    private fun decimal(n: Number): BigDecimal? = when (n) {
        is BigDecimal -> n
        is BigInteger -> BigDecimal(n)
        is Int, is Long, is Short, is Byte -> BigDecimal.valueOf(n.toLong())
        is Double, is Float -> if (n.toDouble().isFinite()) BigDecimal(n.toDouble().toString()) else null
        else -> try { BigDecimal(n.toString()) } catch (e: NumberFormatException) { null }
    }

    private fun typeOf(v: Any?): String = when (v) {
        null -> "null"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> {
            val d = decimal(v)
            if (d == null) "other" else if (d.signum() == 0 || d.stripTrailingZeros().scale() <= 0) "integer" else "number"
        }
        is Map<*, *> -> "object"
        is List<*> -> "array"
        else -> "other"
    }

    private fun equalJson(a: Any?, b: Any?): Boolean {
        if (a is Number && b is Number) {
            val x = decimal(a)
            val y = decimal(b)
            return x != null && y != null && x.compareTo(y) == 0
        }
        if (a is Map<*, *> && b is Map<*, *>) {
            return a.size == b.size && a.all { (k, x) -> b.containsKey(k) && equalJson(x, b[k]) }
        }
        if (a is List<*> && b is List<*>) return a.size == b.size && a.indices.all { equalJson(a[it], b[it]) }
        if (a is JsonElement || b is JsonElement) return equalJson(nativeOf(a), nativeOf(b))
        return a == b
    }

    private fun nativeOf(v: Any?): Any? = if (v is JsonElement) toNative(v, 0) else v

    /** The first violation of [schema] by [value] as a short message without the value, or null when it conforms. */
    private fun violation(value: Any?, schema: Map<String, Any?>, path: String): String? {
        val actual = typeOf(value)
        if (actual == "other") return "$path: not a JSON value"
        val types = typeList(schema["type"])
        if (types.isNotEmpty() && types.none { it == actual || (it == "number" && actual == "integer") }) {
            return "$path: expected ${types.joinToString(" or ")}, got $actual"
        }
        if (schema.containsKey("const") && !equalJson(value, schema["const"])) return "$path: does not equal the required constant"
        val en = schema["enum"]
        if (en is List<*> && en.none { equalJson(it, value) }) return "$path: not one of the allowed values"
        if (value is String) {
            val len = value.codePointCount(0, value.length)
            (schema["minLength"] as? Number)?.let { if (len < it.toLong()) return "$path: shorter than ${it.toLong()}" }
            (schema["maxLength"] as? Number)?.let { if (len > it.toLong()) return "$path: longer than ${it.toLong()}" }
            (schema["pattern"] as? String)?.let { if (!patterns.getValue(it).matcher(value).find()) return "$path: does not match the pattern" }
        }
        if (value is Number) {
            val d = decimal(value)!!
            (schema["minimum"] as? Number)?.let { if (d < decimal(it)!!) return "$path: below ${it}" }
            (schema["maximum"] as? Number)?.let { if (d > decimal(it)!!) return "$path: above ${it}" }
        }
        if (value is List<*>) {
            (schema["minItems"] as? Number)?.let { if (value.size < it.toLong()) return "$path: fewer than ${it.toLong()} items" }
            (schema["maxItems"] as? Number)?.let { if (value.size > it.toLong()) return "$path: more than ${it.toLong()} items" }
            @Suppress("UNCHECKED_CAST")
            val items = schema["items"] as? Map<String, Any?>
            if (items != null) {
                for (i in value.indices) violation(value[i], items, "$path[$i]")?.let { return it }
            }
        }
        if (value is Map<*, *>) {
            (schema["required"] as? List<*>)?.forEach { r -> if (!value.containsKey(r)) return "$path: missing required property \"$r\"" }
            @Suppress("UNCHECKED_CAST")
            val props = schema["properties"] as? Map<String, Any?> ?: emptyMap()
            for ((k, x) in value) {
                if (props.containsKey(k)) {
                    @Suppress("UNCHECKED_CAST")
                    violation(x, props[k] as Map<String, Any?>, "$path.$k")?.let { return it }
                } else if (schema["additionalProperties"] == false) {
                    return "$path: unexpected property \"$k\""
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------- JSON values

    private fun compactJson(v: Any?): String = GSON.toJson(v)

    /** Plain JSON value model (Map, List, String, Number, Boolean, null) for anything this client accepts. */
    private fun toNative(v: Any?, depth: Int): Any? {
        if (depth > MAX_CONVERT_DEPTH) throw MoatRefusal(MoatReason.INPUT_SCHEMA, "the input is nested too deeply")
        return when (v) {
            null, is JsonNull -> null
            is String, is Boolean -> v
            is Double, is Float -> if ((v as Number).toDouble().isFinite()) v else throw notJson()
            is Number -> v
            is JsonPrimitive -> when {
                v.isBoolean -> v.asBoolean
                v.isNumber -> try { StrictJson.parse(v.asString) } catch (e: StrictJson.SyntaxError) { throw notJson() }
                else -> v.asString
            }
            is JsonObject -> LinkedHashMap<String, Any?>().also { m -> for ((k, x) in v.entrySet()) m[k] = toNative(x, depth + 1) }
            is JsonArray -> v.map { toNative(it, depth + 1) }
            is Map<*, *> -> LinkedHashMap<String, Any?>().also { m ->
                for ((k, x) in v) {
                    if (k !is String) throw notJson()
                    m[k] = toNative(x, depth + 1)
                }
            }
            is Iterable<*> -> v.map { toNative(it, depth + 1) }
            is Array<*> -> v.map { toNative(it, depth + 1) }
            else -> throw notJson()
        }
    }

    private fun notJson() = MoatRefusal(MoatReason.INPUT_SCHEMA, "the value is not JSON")

    private companion object {
        const val MAX_MARKER_DEPTH = 64
        const val MAX_CONVERT_DEPTH = 256

        val GSON: Gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

        val KEYWORDS = setOf(
            "type", "properties", "required", "additionalProperties", "items", "enum", "const",
            "minLength", "maxLength", "pattern", "minimum", "maximum", "minItems", "maxItems",
        )
        val TYPES = listOf("object", "array", "string", "number", "integer", "boolean", "null")
        val BAD_ROLES = setOf("system", "developer", "assistant")

        val LINE_ROLE: Pattern = Pattern.compile("^[ \\t]*(system|assistant|developer|tool|user)[ \\t]*:")
        val HEADING: Pattern = Pattern.compile("###\\s*(system|instruction)")
        val ROLE_JSON: Pattern = Pattern.compile("\"role\"\\s*:\\s*\"\\s*(system|developer|assistant)\\s*\"")
        val WHITESPACE: Pattern = Pattern.compile("\\s+")
        val TOKEN_MARKERS = listOf("<|system|>", "<|im_start|>", "<|im_end|>", "[inst]", "<<sys>>")

        /** Default-ignorable and format code points removed before matching (Moat contract), as inclusive ranges. */
        val IGNORABLE: Array<IntRange> = arrayOf(
            0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
            0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF,
            0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
        )

        /** Horizontal whitespace: all Unicode space separators and tab. Line breaks are not in it. */
        val HORIZONTAL: Pattern = Pattern.compile("[\\p{Zs}\\t]+")

        fun isIgnorable(c: Int): Boolean = IGNORABLE.any { c in it }

        private fun isMark(cp: Int): Boolean {
            val t = Character.getType(cp)
            return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
        }

        /**
         * NFKC, then NFKD with nonspacing and enclosing marks dropped, default-ignorable code points removed,
         * confusables mapped to Latin, lower-cased (root locale), horizontal whitespace runs collapsed to one space.
         */
        fun normalise(s: String): String {
            val decomposed = Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKC), Normalizer.Form.NFKD)
            val sb = StringBuilder(decomposed.length)
            var i = 0
            while (i < decomposed.length) {
                val cp = decomposed.codePointAt(i)
                if (!isMark(cp) && !isIgnorable(cp)) sb.append(MOAT_CONFUSABLES[cp] ?: String(Character.toChars(cp)))
                i += Character.charCount(cp)
            }
            return HORIZONTAL.matcher(sb.toString().lowercase(Locale.ROOT)).replaceAll(" ")
        }
    }
}
