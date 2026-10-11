package io.trimio.engine.models

import org.kotlincrypto.hash.sha2.SHA512

/**
 * Ed25519 signature verification (RFC 8032), pure Kotlin so it runs the same on Android, iOS,
 * desktop and the web. A port of TweetNaCl's `crypto_sign_open` (public domain): field elements are
 * sixteen 16-bit limbs in Longs. Verification only — the app never holds a private key; packs are
 * signed on the server (docs/SERVER.md). Non-canonical S (≥ L) is rejected, so a signature cannot be
 * re-shaped into a second valid one.
 */
object Ed25519 {
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** True when [signature] is [publicKey]'s signature of [message]. Never throws on bad input. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) return false
        if (!canonicalS(signature)) return false
        val q = Array(4) { gf() }
        if (!unpackNeg(q, publicKey)) return false
        val h = SHA512().apply {
            update(signature, 0, 32)
            update(publicKey)
            update(message)
        }.digest()
        val hr = LongArray(64) { h[it].toLong() and 0xff }
        val hs = ByteArray(32)
        modL(hs, hr)
        val p = Array(4) { gf() }
        scalarMult(p, q, hs)
        val b = Array(4) { gf() }
        scalarBase(b, signature.copyOfRange(32, 64))
        add(p, b)
        val t = ByteArray(32)
        pack(t, p)
        var diff = 0
        for (i in 0 until 32) diff = diff or (signature[i].toInt() xor t[i].toInt())
        return diff == 0
    }

    // ------------------------------------------------------------------------- field arithmetic

    private fun gf(init: LongArray? = null) = LongArray(16).also { if (init != null) init.copyInto(it) }

    private val GF0 = gf()
    private val GF1 = gf().also { it[0] = 1 }
    private val D = longArrayOf(0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203)
    private val D2 = longArrayOf(0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406)
    private val X = longArrayOf(0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169)
    private val Y = longArrayOf(0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666)
    private val I = longArrayOf(0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83)

    /** The group order L, little-endian. */
    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(o: ByteArray, n: LongArray) {
        val m = gf()
        val t = gf(n)
        car(t); car(t); car(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun neq(a: LongArray, b: LongArray): Boolean {
        val c = ByteArray(32)
        val d = ByteArray(32)
        pack25519(c, a)
        pack25519(d, b)
        return !c.contentEquals(d)
    }

    private fun par(a: LongArray): Int {
        val d = ByteArray(32)
        pack25519(d, a)
        return d[0].toInt() and 1
    }

    private fun unpack25519(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun addF(o: LongArray, x: LongArray, y: LongArray) { for (i in 0 until 16) o[i] = x[i] + y[i] }

    private fun subF(o: LongArray, x: LongArray, y: LongArray) { for (i in 0 until 16) o[i] = x[i] - y[i] }

    private fun mulF(o: LongArray, x: LongArray, y: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += x[i] * y[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o); car(o)
    }

    private fun sqF(o: LongArray, x: LongArray) = mulF(o, x, x)

    private fun pow2523(o: LongArray, i: LongArray) {
        val c = gf(i)
        for (k in 250 downTo 0) {
            sqF(c, c)
            if (k != 1) mulF(c, c, i)
        }
        c.copyInto(o)
    }

    private fun inv(o: LongArray, i: LongArray) {
        val c = gf(i)
        for (k in 253 downTo 0) {
            sqF(c, c)
            if (k != 2 && k != 4) mulF(c, c, i)
        }
        c.copyInto(o)
    }

    // --------------------------------------------------------------------------- group points

    private fun add(p: Array<LongArray>, q: Array<LongArray>) {
        val a = gf(); val b = gf(); val c = gf(); val d = gf(); val t = gf()
        val e = gf(); val f = gf(); val g = gf(); val h = gf()
        subF(a, p[1], p[0]); subF(t, q[1], q[0]); mulF(a, a, t)
        addF(b, p[0], p[1]); addF(t, q[0], q[1]); mulF(b, b, t)
        mulF(c, p[3], q[3]); mulF(c, c, D2)
        mulF(d, p[2], q[2]); addF(d, d, d)
        subF(e, b, a); subF(f, d, c); addF(g, d, c); addF(h, b, a)
        mulF(p[0], e, f); mulF(p[1], h, g); mulF(p[2], g, f); mulF(p[3], e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, b: Int) { for (i in 0 until 4) sel(p[i], q[i], b) }

    private fun pack(r: ByteArray, p: Array<LongArray>) {
        val tx = gf(); val ty = gf(); val zi = gf()
        inv(zi, p[2])
        mulF(tx, p[0], zi)
        mulF(ty, p[1], zi)
        pack25519(r, ty)
        r[31] = (r[31].toInt() xor (par(tx) shl 7)).toByte()
    }

    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        GF0.copyInto(p[0]); GF1.copyInto(p[1]); GF1.copyInto(p[2]); GF0.copyInto(p[3])
        for (i in 255 downTo 0) {
            val b = (s[i / 8].toInt() shr (i and 7)) and 1
            cswap(p, q, b)
            add(q, p)
            add(p, p)
            cswap(p, q, b)
        }
    }

    private fun scalarBase(p: Array<LongArray>, s: ByteArray) {
        val q = arrayOf(gf(X), gf(Y), gf(GF1), gf())
        mulF(q[3], X, Y)
        scalarMult(p, q, s)
    }

    private fun modL(r: ByteArray, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
    }

    /** -A from its encoding (the verifier works with the negated key); false for a point not on the curve. */
    private fun unpackNeg(r: Array<LongArray>, p: ByteArray): Boolean {
        val t = gf(); val chk = gf(); val num = gf(); val den = gf(); val den2 = gf(); val den4 = gf(); val den6 = gf()
        GF1.copyInto(r[2])
        unpack25519(r[1], p)
        sqF(num, r[1])
        mulF(den, num, D)
        subF(num, num, r[2])
        addF(den, r[2], den)
        sqF(den2, den); sqF(den4, den2); mulF(den6, den4, den2); mulF(t, den6, num); mulF(t, t, den)
        pow2523(t, t)
        mulF(t, t, num); mulF(t, t, den); mulF(t, t, den); mulF(r[0], t, den)
        sqF(chk, r[0]); mulF(chk, chk, den)
        if (neq(chk, num)) mulF(r[0], r[0], I)
        sqF(chk, r[0]); mulF(chk, chk, den)
        if (neq(chk, num)) return false
        if (par(r[0]) == ((p[31].toInt() and 0xff) shr 7)) subF(r[0], GF0, r[0])
        mulF(r[3], r[0], r[1])
        return true
    }

    /** S < L (RFC 8032 §5.1.7). */
    private fun canonicalS(sig: ByteArray): Boolean {
        for (i in 31 downTo 0) {
            val v = sig[32 + i].toLong() and 0xff
            if (v < L[i]) return true
            if (v > L[i]) return false
        }
        return false
    }
}
