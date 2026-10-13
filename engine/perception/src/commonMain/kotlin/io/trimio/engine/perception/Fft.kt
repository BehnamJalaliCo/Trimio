package io.trimio.engine.perception

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT of a real or complex signal; [re]/[im] length must be a power of two. */
object Fft {
    fun transform(re: FloatArray, im: FloatArray) {
        val n = re.size
        require(n == im.size && n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two, got $n" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tr = (re[b] * cr - im[b] * ci).toFloat()
                    val ti = (re[b] * ci + im[b] * cr).toFloat()
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
    }

    /** A periodic Hann window (torch.hann_window's default), 0.5 − 0.5·cos(2πn/N). */
    fun hann(n: Int): FloatArray = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / n)).toFloat() }

    /**
     * Magnitude spectrogram, frames × (nFft/2+1), torch.stft style: `center=true` with reflect
     * padding, [window] of length nFft, magnitudes scaled by [scale].
     */
    fun magnitudes(x: FloatArray, nFft: Int, hop: Int, window: FloatArray, scale: Float = 1f): Array<FloatArray> {
        val pad = nFft / 2
        val frames = 1 + x.size / hop
        val bins = nFft / 2 + 1
        val re = FloatArray(nFft)
        val im = FloatArray(nFft)
        return Array(frames) { f ->
            for (k in 0 until nFft) {
                re[k] = reflect(x, f * hop + k - pad) * window[k]
                im[k] = 0f
            }
            transform(re, im)
            FloatArray(bins) { b -> sqrt(re[b] * re[b] + im[b] * im[b]) * scale }
        }
    }

    private fun reflect(x: FloatArray, i: Int): Float {
        if (x.isEmpty()) return 0f
        if (x.size == 1) return x[0]
        var k = i
        val last = x.size - 1
        while (k < 0 || k > last) k = if (k < 0) -k else 2 * last - k
        return x[k]
    }
}
