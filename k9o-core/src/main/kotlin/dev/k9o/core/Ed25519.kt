package dev.k9o.core

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature verification (RFC 8032, section 5.1.7) in plain `BigInteger` arithmetic.
 *
 * The JDK's own `Signature.getInstance("Ed25519")` exists from JDK 15, and Android's platform provider does not
 * offer it on older releases, so the client carries this verifier and never depends on the provider: it behaves the
 * same on every JVM and every Android release the library runs on. It only verifies; it holds no secret, so constant
 * time is not needed. A verification takes a few milliseconds.
 *
 * [verify] is cofactorless: it accepts when `[S]B == R + [h]A`, rejects a non-canonical `S` (`S >= L`), and rejects a
 * public key or `R` that is not a valid point encoding.
 */
internal object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    // Extended homogeneous coordinates (X, Y, Z, T): x = X/Z, y = Y/Z, x*y = T/Z.
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val num = y.multiply(y).subtract(BigInteger.ONE)
        val den = D.multiply(y).multiply(y).add(BigInteger.ONE)
        val x2 = num.multiply(den.modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if (x.multiply(x).subtract(x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x)
        return x
    }

    private val BASE: Pt = run {
        val by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val bx = recoverX(by, 0)!!
        Pt(bx, by, BigInteger.ONE, bx.multiply(by).mod(P))
    }
    private val IDENTITY = Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun add(a: Pt, b: Pt): Pt {
        val a1 = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P)
        val b1 = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P)
        val c1 = a.t.multiply(b.t).multiply(D).shiftLeft(1).mod(P)
        val d1 = a.z.multiply(b.z).shiftLeft(1).mod(P)
        val e = b1.subtract(a1)
        val f = d1.subtract(c1)
        val g = d1.add(c1)
        val h = b1.add(a1)
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Pt): Pt {
        var result = IDENTITY
        var addend = point
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) result = add(result, addend)
            addend = add(addend, addend)
        }
        return result
    }

    private fun equal(a: Pt, b: Pt): Boolean =
        a.x.multiply(b.z).subtract(b.x.multiply(a.z)).mod(P).signum() == 0 &&
            a.y.multiply(b.z).subtract(b.y.multiply(a.z)).mod(P).signum() == 0

    private fun littleEndian(b: ByteArray, from: Int, len: Int): BigInteger {
        val be = ByteArray(len + 1) // leading zero byte keeps the value non-negative
        for (i in 0 until len) be[len - i] = b[from + i]
        return BigInteger(be)
    }

    private fun decode(b: ByteArray, from: Int): Pt? {
        val v = littleEndian(b, from, 32)
        val sign = if (v.testBit(255)) 1 else 0
        val y = v.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    /** True when [signature] (64 bytes) is a valid Ed25519 signature of [message] under [publicKey] (32 bytes). */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey, 0) ?: return false
        val r = decode(signature, 0) ?: return false
        val s = littleEndian(signature, 32, 32)
        if (s >= L) return false
        val md = MessageDigest.getInstance("SHA-512")
        md.update(signature, 0, 32)
        md.update(publicKey)
        md.update(message)
        val digest = md.digest()
        val h = littleEndian(digest, 0, 64).mod(L)
        return equal(mul(s, BASE), add(r, mul(h, a)))
    }
}
