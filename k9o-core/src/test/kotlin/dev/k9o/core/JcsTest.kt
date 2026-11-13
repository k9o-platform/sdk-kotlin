package dev.k9o.core

import com.google.gson.JsonParser
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** The platform's JCS code-point profile: the 25 shared cases plus the edges of each rule. */
class JcsTest {
    @Test
    fun `every shared case`() {
        val stream = JcsTest::class.java.getResourceAsStream("/fixtures/jcs-codepoint-cases.json")
        assertNotNull(stream)
        val cases = stream.reader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("cases") }
        assertEquals(25, cases.size())
        var errors = 0
        for (el in cases) {
            val c = el.asJsonObject
            val name = c.get("name").asString
            val input = c.get("input")
            if (c.has("expectCanonical")) {
                assertEquals(c.get("expectCanonical").asString, Jcs.canonicalize(input), name)
            } else {
                errors++
                assertFailsWith<IllegalArgumentException>(name) { Jcs.canonicalize(input) }
            }
        }
        assertEquals(true, errors > 0)
    }

    @Test
    fun `native values give the same text as Gson elements`() {
        val tree = JsonParser.parseString("""{ "b": [1, 2, {"z": null, "a": true}], "a": "x" }""")
        val native = mapOf("b" to listOf(1, 2L, mapOf("z" to null, "a" to true)), "a" to "x")
        assertEquals("""{"a":"x","b":[1,2,{"a":true,"z":null}]}""", Jcs.canonicalize(tree))
        assertEquals(Jcs.canonicalize(tree), Jcs.canonicalize(native))
        assertEquals("{}", Jcs.canonicalize(emptyMap<String, Any?>()))
        assertEquals("[]", Jcs.canonicalize(emptyList<Any?>()))
        assertEquals("[1,\"a\"]", Jcs.canonicalize(arrayOf<Any?>(1, "a")))
    }

    @Test
    fun `nan infinity and fractions are rejected`() {
        for (bad in listOf<Any>(Double.NaN, Double.POSITIVE_INFINITY, Float.NaN, 0.5, BigDecimal("4.50"), listOf(1, 2.5), mapOf("x" to Float.NaN))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { Jcs.canonicalize(bad) }
        }
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(JsonParser.parseString("[1.25]")) }
    }

    @Test
    fun `integers only up to 2 to the 53 minus 1`() {
        assertEquals("9007199254740991", Jcs.canonicalize(9007199254740991L))
        assertEquals("-9007199254740991", Jcs.canonicalize(BigInteger.valueOf(-9007199254740991L)))
        for (bad in listOf<Any>(9007199254740992L, BigInteger.TEN.pow(30), 1e30, BigDecimal("1E30"), BigDecimal("1E999999999"))) {
            assertFailsWith<IllegalArgumentException>(bad.toString()) { Jcs.canonicalize(bad) }
        }
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(JsonParser.parseString("9007199254740992")) }
    }

    @Test
    fun `whole numbers of other types are integers`() {
        assertEquals("1", Jcs.canonicalize(1.0))
        assertEquals("0", Jcs.canonicalize(-0.0))
        assertEquals("100", Jcs.canonicalize(BigDecimal("1E2")))
        assertEquals("-12", Jcs.canonicalize(-12L))
        assertEquals("0", Jcs.canonicalize(JsonParser.parseString("-0")))
    }

    @Test
    fun `keys sort by code point not utf16 unit`() {
        val grin = String(Character.toChars(0x1F600))
        val ligature = 0xFB00.toChar().toString()
        val m = linkedMapOf<String, Any?>(grin to 1, ligature to 2, "b" to 3, "a" to 4)
        assertEquals("{\"a\":4,\"b\":3,\"$ligature\":2,\"$grin\":1}", Jcs.canonicalize(m))
    }

    @Test
    fun `string escapes and surrogates`() {
        val input = "\b\t\n" + 12.toChar() + "\r" + 0.toChar() + 0x1f.toChar() + "\"\\/" + 0x7f.toChar() + " "
        assertEquals("\"\\b\\t\\n\\f\\r\\u0000\\u001f\\\"\\\\/" + 0x7f.toChar() + " \"", Jcs.canonicalize(input))
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(0xD83D.toChar().toString()) }
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(0xDE00.toChar().toString() + "x") }
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(mapOf(0xD800.toChar().toString() to 1)) }
    }

    @Test
    fun `other types and non-string keys are rejected`() {
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(Any()) }
        assertFailsWith<IllegalArgumentException> { Jcs.canonicalize(mapOf(1 to "a")) }
    }
}
