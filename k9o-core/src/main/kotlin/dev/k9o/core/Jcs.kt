package dev.k9o.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger

/**
 * The Kilimanjaro JCS code-point profile, written here so the client needs no extra dependency.
 *
 * Object keys are sorted by UTF-8 byte order (the same as Unicode code-point order), there is no whitespace, and
 * strings are escaped minimally: `"` and `\` with a backslash, the short escapes for backspace, tab, newline, form
 * feed and carriage return, every other control character below U+0020 as a lowercase four-digit hex escape; `/` and
 * every other code point are written as they are. Numbers are integers only: base 10, no exponent, `-0` written `0`.
 * A fractional number, NaN, an infinity, an integer above 9007199254740991 (2^53 - 1) in absolute value, or a string
 * with an unpaired surrogate throws [IllegalArgumentException].
 *
 * Accepts `null`, `Boolean`, `String`, any `Number`, `Map<String, *>`, `Iterable`, `Array<*>`, and Gson's
 * `JsonElement`. Anything else throws [IllegalArgumentException].
 */
object Jcs {
    private const val MAX_DEPTH = 256
    private const val MAX_SAFE = 9_007_199_254_740_991L

    /** The canonical form as text. */
    @JvmStatic
    fun canonicalize(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value, 0)
        return sb.toString()
    }

    /** The canonical form as UTF-8 bytes. */
    @JvmStatic
    fun canonicalBytes(value: Any?): ByteArray = canonicalize(value).toByteArray(Charsets.UTF_8)

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("JSON nesting is too deep to canonicalize")
        when (v) {
            null, is JsonNull -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is JsonPrimitive -> when {
                v.isBoolean -> sb.append(if (v.asBoolean) "true" else "false")
                v.isNumber -> sb.append(numberToString(v.asNumber))
                else -> writeString(sb, v.asString)
            }
            is Number -> sb.append(numberToString(v))
            is JsonObject -> writeMap(sb, v.entrySet().associate { it.key to it.value }, depth)
            is JsonArray -> writeList(sb, v, depth)
            is Map<*, *> -> {
                val m = LinkedHashMap<String, Any?>(v.size)
                for ((k, x) in v) {
                    if (k !is String) throw IllegalArgumentException("object keys must be strings")
                    m[k] = x
                }
                writeMap(sb, m, depth)
            }
            is Iterable<*> -> writeList(sb, v, depth)
            is Array<*> -> writeList(sb, v.asList(), depth)
            else -> throw IllegalArgumentException("cannot canonicalize a ${v.javaClass.name}")
        }
    }

    private fun writeMap(sb: StringBuilder, m: Map<String, Any?>, depth: Int) {
        val keys = m.keys.sortedWith(::compareCodePoints)
        sb.append('{')
        var first = true
        for (k in keys) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k)
            sb.append(':')
            write(sb, m[k], depth + 1)
        }
        sb.append('}')
    }

    private fun writeList(sb: StringBuilder, l: Iterable<*>, depth: Int) {
        sb.append('[')
        var first = true
        for (e in l) {
            if (!first) sb.append(',')
            first = false
            write(sb, e, depth + 1)
        }
        sb.append(']')
    }

    private val HEX = "0123456789abcdef"

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\t' -> sb.append("\\t")
                c == '\n' -> sb.append("\\n")
                c.code == 12 -> sb.append("\\f")
                c == '\r' -> sb.append("\\r")
                c.code < 0x20 -> sb.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 15])
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) {
                        throw IllegalArgumentException("string has an unpaired surrogate")
                    }
                    sb.append(c).append(s[++i])
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("string has an unpaired surrogate")
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    /** Orders strings by code point, which is the order of their UTF-8 bytes. */
    internal fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private fun numberToString(n: Number): String = when (n) {
        is Int, is Long, is Short, is Byte,
        is java.util.concurrent.atomic.AtomicInteger, is java.util.concurrent.atomic.AtomicLong -> safeLong(n.toLong())
        is BigInteger -> safeBig(n)
        is BigDecimal -> fromDecimal(n)
        is Double, is Float -> {
            val d = n.toDouble()
            if (d.isNaN() || d.isInfinite()) throw IllegalArgumentException("NaN and Infinity cannot be canonicalized")
            if (d != Math.rint(d)) throw IllegalArgumentException("a non-integer number cannot be canonicalized")
            if (Math.abs(d) > MAX_SAFE) throw IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized")
            d.toLong().toString()
        }
        // Gson's lazily parsed numbers and any other Number: go through its decimal text.
        else -> fromDecimal(
            try { BigDecimal(n.toString()) } catch (e: NumberFormatException) {
                throw IllegalArgumentException("a number that is not finite cannot be canonicalized")
            },
        )
    }

    private fun fromDecimal(bd: BigDecimal): String {
        if (bd.signum() == 0) return "0"
        val s = bd.stripTrailingZeros()
        if (s.scale() > 0) throw IllegalArgumentException("a non-integer number cannot be canonicalized")
        // digits before the point; 2^53-1 has 16, so more than 16 is out of range without building the integer
        if (s.precision().toLong() - s.scale().toLong() > 16) throw IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized")
        return safeBig(s.toBigIntegerExact())
    }

    private fun safeLong(l: Long): String {
        if (l > MAX_SAFE || l < -MAX_SAFE) throw IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized")
        return l.toString()
    }

    private fun safeBig(b: BigInteger): String {
        if (b.abs() > BigInteger.valueOf(MAX_SAFE)) throw IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized")
        return b.toString()
    }
}
