package io.trimio.engine.retrieval

/**
 * Precomputed int8 vectors of one corpus in one vector space ([io.trimio.engine.llm.TextEmbedder.space]:
 * embedder id, dims and prompt version), built offline by tools/retrieval with the same embedder
 * the device runs. Each entry carries its document's fingerprint ([Doc.fingerprint]): a vector is
 * reused only while the document still reads the same, and packs of another space are refused.
 *
 * Binary layout (little-endian): `TRVP`, version byte, space and corpus (u16 length + UTF-8),
 * dims (u16), count (u32), then per entry: id (u16 length + UTF-8), fingerprint (i32), scale
 * (f32), dims bytes. A bundle ([writeAll]/[readAll]) is `TRVB`, count (u16), then each pack as
 * its length (u32) and bytes.
 */
class VectorPack(val space: String, val corpus: String, val dims: Int, val entries: List<Entry>) {
    class Entry(val id: String, val fingerprint: Int, val scale: Float, val bytes: ByteArray)

    fun write(): ByteArray {
        val w = Writer()
        w.raw(MAGIC.encodeToByteArray())
        w.byte(VERSION)
        w.text(space)
        w.text(corpus)
        w.u16(dims)
        w.i32(entries.size)
        for (e in entries) {
            require(e.bytes.size == dims)
            w.text(e.id)
            w.i32(e.fingerprint)
            w.i32(e.scale.toRawBits())
            w.raw(e.bytes)
        }
        return w.bytes()
    }

    companion object {
        private const val MAGIC = "TRVP"
        private const val BUNDLE = "TRVB"
        private const val VERSION = 1

        fun read(bytes: ByteArray): VectorPack = read(Reader(bytes))

        private fun read(r: Reader): VectorPack {
            require(r.raw(MAGIC.length).decodeToString() == MAGIC) { "not a vector pack" }
            val version = r.byte()
            require(version == VERSION) { "vector pack version $version is not supported" }
            val space = r.text()
            val corpus = r.text()
            val dims = r.u16()
            val n = r.i32()
            val entries = List(n) { Entry(r.text(), r.i32(), Float.fromBits(r.i32()), r.raw(dims)) }
            return VectorPack(space, corpus, dims, entries)
        }

        fun writeAll(packs: List<VectorPack>): ByteArray {
            val w = Writer()
            w.raw(BUNDLE.encodeToByteArray())
            w.u16(packs.size)
            for (p in packs) {
                val b = p.write()
                w.i32(b.size)
                w.raw(b)
            }
            return w.bytes()
        }

        /** Every pack of a bundle (or the single pack of a plain pack file). */
        fun readAll(bytes: ByteArray): List<VectorPack> {
            if (bytes.size >= MAGIC.length && bytes.copyOfRange(0, MAGIC.length).decodeToString() == MAGIC) return listOf(read(bytes))
            val r = Reader(bytes)
            require(r.raw(BUNDLE.length).decodeToString() == BUNDLE) { "not a vector pack bundle" }
            return List(r.u16()) { read(r.raw(r.i32())) }
        }
    }

    private class Writer {
        private var buf = ByteArray(1 shl 16)
        private var n = 0

        private fun room(k: Int) {
            if (n + k > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + k))
        }

        fun byte(v: Int) {
            room(1)
            buf[n++] = v.toByte()
        }

        fun u16(v: Int) {
            require(v in 0..0xFFFF)
            byte(v and 0xFF)
            byte(v ushr 8 and 0xFF)
        }

        fun i32(v: Int) {
            for (k in 0 until 4) byte(v ushr (8 * k) and 0xFF)
        }

        fun raw(b: ByteArray) {
            room(b.size)
            b.copyInto(buf, n)
            n += b.size
        }

        fun text(s: String) {
            val b = s.encodeToByteArray()
            u16(b.size)
            raw(b)
        }

        fun bytes(): ByteArray = buf.copyOf(n)
    }

    private class Reader(private val b: ByteArray) {
        private var at = 0

        fun byte(): Int = b[at++].toInt() and 0xFF

        fun u16(): Int = byte() or (byte() shl 8)

        fun i32(): Int = byte() or (byte() shl 8) or (byte() shl 16) or (byte() shl 24)

        fun raw(k: Int): ByteArray {
            require(at + k <= b.size) { "vector pack is truncated" }
            return b.copyOfRange(at, at + k).also { at += k }
        }

        fun text(): String = raw(u16()).decodeToString()
    }
}
