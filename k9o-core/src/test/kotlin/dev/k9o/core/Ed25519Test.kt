package dev.k9o.core

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** The in-house RFC 8032 verifier against the published vectors, and against the JDK's own signer (test only). */
class Ed25519Test {
    // RFC 8032 section 7.1, vectors 1 to 3: public key, message, signature.
    private val vectors = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun `rfc 8032 vectors verify and tampering fails`() {
        assertEquals(3, vectors.size)
        for ((pub, msg, sig) in vectors) {
            val pk = hex(pub)
            val m = hex(msg)
            val s = hex(sig)
            assertTrue(Ed25519.verify(pk, m, s), pub)
            assertFalse(Ed25519.verify(pk, m + byteArrayOf(0x78), s))
            for (i in listOf(10, 40)) {
                val flipped = s.copyOf()
                flipped[i] = (flipped[i].toInt() xor 1).toByte()
                assertFalse(Ed25519.verify(pk, m, flipped), "byte $i")
            }
            val badKey = pk.copyOf()
            badKey[3] = (badKey[3].toInt() xor 1).toByte()
            assertFalse(Ed25519.verify(badKey, m, s))
        }
    }

    @Test
    fun `non canonical s and bad encodings are rejected`() {
        val (pub, _, sig) = vectors[0]
        val pk = hex(pub)
        val s = hex(sig)
        // S + L is the same point equation but not canonical
        val l = java.math.BigInteger.ONE.shiftLeft(252).add(java.math.BigInteger("27742317777372353535851937790883648493"))
        val sInt = java.math.BigInteger(1, s.copyOfRange(32, 64).reversedArray()).add(l)
        val be = sInt.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val nonCanonical = s.copyOfRange(0, 32) + be.reversedArray()
        assertFalse(Ed25519.verify(pk, ByteArray(0), nonCanonical))
        assertFalse(Ed25519.verify(pk.copyOf(31), ByteArray(0), s))
        assertFalse(Ed25519.verify(pk, ByteArray(0), s.copyOf(63)))
        assertFalse(Ed25519.verify(ByteArray(32) { 0xff.toByte() }, ByteArray(0), s)) // y >= p
        assertTrue(Ed25519.verify(pk, ByteArray(0), s))
    }

    @Test
    fun `agrees with the jdk signer on random keys and messages`() {
        val rnd = Random(8032)
        val gen = KeyPairGenerator.getInstance("Ed25519")
        repeat(25) { n ->
            val pair = gen.generateKeyPair()
            val der = pair.public.encoded
            val raw = der.copyOfRange(der.size - 32, der.size)
            val msg = ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }
            val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
            assertTrue(Ed25519.verify(raw, msg, sig), "case $n")
            assertFalse(Ed25519.verify(raw, msg + byteArrayOf(1), sig), "case $n tampered")
            assertContentEquals(sig, sig.copyOf())
        }
    }
}
