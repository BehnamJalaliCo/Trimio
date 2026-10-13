package io.trimio.engine.asr.whisper

import io.trimio.core.model.text.Language
import io.trimio.engine.asr.AsrScore
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * A/B of speech models on our Persian benchmark (docs/benchmark/01-repo-map: the owner's 26 s talk,
 * gold words hand-corrected): word error rate and glossary-term errors per model. Opt-in, with the
 * host library and the models (never committed):
 * `-Ptrimio.nativeTests -Ptrimio.asrModels=a.bin,b.bin -Ptrimio.asrAudio=<16 kHz mono wav>`.
 */
class PersianAbTest {
    private val models = System.getProperty("trimio.asr.models")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
    private val audio = System.getProperty("trimio.asr.audio")?.let(::File)?.takeIf { it.isFile }

    @kotlin.test.Test
    fun compareModelsOnTheBenchmark() {
        if (System.getProperty("trimio.nativeTests") != "true" || models.isEmpty() || audio == null) return
        val gold = Json.parseToJsonElement(File("../../docs/benchmark/01-repo-map/words.json").readText()).jsonArray
            .joinToString(" ") { it.jsonObject.getValue("w").jsonPrimitive.content }
        val pcm = WavCodec.decodeMono(audio.readBytes())
        val prompt = "این ویدیو درباره‌ی Claude Code، Codex، OpenCode و ChatGPT است."
        for (model in models) {
            for (withPrompt in listOf(false, true)) {
                WhisperCppRecognizer(model.path).use { r ->
                    val t = System.nanoTime()
                    val text = runBlocking { r.transcribe(pcm, RecognitionOptions(Language.Persian, initialPrompt = prompt.takeIf { withPrompt })) }.text
                    val s = AsrScore.of(gold, text, GLOSSARY)
                    println("server A/B ${model.name}${if (withPrompt) " +glossary prompt" else ""}: ${s.summary()} in ${(System.nanoTime() - t) / 1_000_000} ms")
                    println("  $text")
                }
            }
        }
    }

    private companion object {
        val GLOSSARY = listOf("Claude Code", "Codex", "OpenCode", "ChatGPT", "ریپو", "پروژه", "توکن", "مپ", "نصب", "صرفه‌جویی", "کد")
    }
}
