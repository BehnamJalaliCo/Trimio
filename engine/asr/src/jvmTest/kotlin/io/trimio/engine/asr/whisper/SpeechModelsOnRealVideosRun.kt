package io.trimio.engine.asr.whisper

import io.trimio.core.model.text.Language
import io.trimio.engine.asr.AsrScore
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Whether a faster speech model loses words on the owner's own talks: every recording read by the reference
 * (the first model, full large-v3) and by each candidate (turbo), the candidate scored against the reference
 * word by word, with the differing words printed side by side for a person to judge (a spelling variant is
 * not a lost word). Opt-in, with the host library and the models (never committed):
 * `-Ptrimio.nativeTests -Ptrimio.asrModels=reference.bin,candidate.bin -Ptrimio.asrWavs=a.wav,b.wav`.
 */
class SpeechModelsOnRealVideosRun {
    private val models = System.getProperty("trimio.asr.models")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
    private val wavs = System.getProperty("trimio.asr.wavs")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()

    @kotlin.test.Test
    fun candidatesAgainstTheReference() {
        if (System.getProperty("trimio.nativeTests") != "true" || models.size < 2 || wavs.isEmpty()) return
        val texts = models.associateWith { model ->
            WhisperCppRecognizer(model.path).use { r ->
                wavs.map { wav ->
                    val pcm = WavCodec.decodeMono(wav.readBytes())
                    val t = System.nanoTime()
                    val text = runBlocking { r.transcribe(pcm, RecognitionOptions(Language.Persian)) }.text
                    val seconds = (System.nanoTime() - t) / 1e9
                    println("speech ${model.name} ${wav.name}: ${"%.1f".format(seconds)} s for ${"%.1f".format(pcm.samples.size / 16_000.0)} s of sound")
                    text
                }
            }
        }
        val reference = models.first()
        for (candidate in models.drop(1)) {
            wavs.forEachIndexed { i, wav ->
                val ref = texts.getValue(reference)[i]
                val hyp = texts.getValue(candidate)[i]
                println("speech A/B ${wav.name} ${candidate.name} vs ${reference.name}: ${AsrScore.of(ref, hyp).summary()}")
                differences(ref, hyp).forEach { println("  $it") }
            }
        }
    }

    /** The reference's words the candidate wrote otherwise or left out, in order («ref → hyp»; «—» is nothing). */
    private fun differences(ref: String, hyp: String): List<String> {
        val a = ref.split(Regex("\\s+")).filter { it.isNotBlank() }
        val b = hyp.split(Regex("\\s+")).filter { it.isNotBlank() }
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (same(a[i - 1], b[j - 1])) 0 else 1)
        }
        val out = ArrayList<String>()
        var i = a.size
        var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (same(a[i - 1], b[j - 1])) 0 else 1 -> {
                    if (!same(a[i - 1], b[j - 1])) out += "«${a[i - 1]}» → «${b[j - 1]}»"
                    i--; j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { out += "«${a[i - 1]}» → —"; i-- }
                else -> { out += "— → «${b[j - 1]}»"; j-- }
            }
        }
        return out.reversed()
    }

    private fun same(x: String, y: String) = key(x) == key(y)

    private fun key(w: String) = w.filter { it.isLetterOrDigit() }.replace('ي', 'ی').replace('ك', 'ک')
}
