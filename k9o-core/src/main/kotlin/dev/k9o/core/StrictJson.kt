package dev.k9o.core

import java.math.BigInteger

/**
 * A strict JSON reader (RFC 8259) for text that is not trusted: receipts and model output. No comments, no trailing
 * commas, no single quotes, no NaN, one value and nothing after it. A duplicate object name keeps the last value, as
 * `JSON.parse` does. Values come back as `Map<String, Any?>` (insertion order), `List<Any?>`, `String`, `Boolean`,
 * `null`, and numbers as `Long` (an integer literal that fits), `BigInteger` (a larger integer literal) or `Double`.
 */
internal object StrictJson {
    class SyntaxError(message: String) : IllegalArgumentException(message)

    private const val MAX_DEPTH = 512

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw SyntaxError("unexpected text after the value")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw SyntaxError("nesting is too deep")
            if (pos >= s.length) throw SyntaxError("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw SyntaxError("unexpected character")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw SyntaxError("unexpected character")
            pos += word.length
            return v
        }

        fun obj(depth: Int): Any? {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw SyntaxError("expected a name")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw SyntaxError("expected ':'")
                pos++
                skipWs()
                m.remove(k) // last value wins, and the name keeps its latest position
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw SyntaxError("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw SyntaxError("expected ',' or '}'")
                }
            }
        }

        fun arr(depth: Int): Any? {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw SyntaxError("unexpected end")
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw SyntaxError("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw SyntaxError("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> throw SyntaxError("control character in a string")
                    c == '\\' -> {
                        if (pos >= s.length) throw SyntaxError("unterminated string")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append(12.toChar())
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw SyntaxError("bad unicode escape")
                                var code = 0
                                for (i in 0 until 4) {
                                    val d = Character.digit(s[pos + i], 16)
                                    if (d < 0 || s[pos + i].code > 127) throw SyntaxError("bad unicode escape")
                                    code = code * 16 + d
                                }
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw SyntaxError("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Any? {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) throw SyntaxError("bad number")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else throw SyntaxError("bad number")
            var integral = true
            if (pos < s.length && s[pos] == '.') {
                integral = false
                pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) throw SyntaxError("bad number")
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val d = pos
                while (pos < s.length && s[pos] in '0'..'9') pos++
                if (pos == d) throw SyntaxError("bad number")
            }
            val lit = s.substring(start, pos)
            if (integral) {
                val big = BigInteger(lit)
                return if (big.bitLength() < 64) big.toLong() else big
            }
            return lit.toDouble()
        }
    }
}
