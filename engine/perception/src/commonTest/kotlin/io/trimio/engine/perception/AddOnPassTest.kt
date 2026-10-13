package io.trimio.engine.perception

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Accelerator
import io.trimio.engine.onnx.NeuralEngine
import io.trimio.engine.onnx.NeuralEngineException
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.SessionConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddOnPassTest {
    private val words = listOf(word("سلام", 0, 400), word("دوستان", 450, 900))
    private val audio = PcmAudio(FloatArray(PcmAudio.WHISPER_RATE), PcmAudio.WHISPER_RATE)
    private val frame = Frame(4, 4, ByteArray(4 * 4 * 3))

    @Test
    fun nothingInstalledChangesNothingAndReportsNothing() = runTest {
        val pass = AddOnPass(Perception())
        assertSame(words, pass.words(words, audio, Language.Persian))
        assertNull(pass.head(listOf(frame)))
        assertTrue(pass.report.isEmpty())
    }

    @Test
    fun aFailingAddOnFallsBackAndIsReported() = runTest {
        val pass = AddOnPass(Perception(Broken, ModelFiles { "/missing/${it.fileName}" }))
        assertNull(pass.head(listOf(frame)))
        assertEquals(words, pass.words(words, audio, Language.Persian))
        assertTrue(pass.report.any { it.startsWith("faces:") }, pass.report.toString())
        assertTrue(pass.report.none { "applied" in it }, pass.report.toString())
        assertTrue(Perception(Broken, ModelFiles { "/x" }).has(MediaAddOns.faces))
    }

    private object Broken : NeuralEngine {
        override val name = "broken"

        override fun available() = true

        override fun accelerators() = setOf(Accelerator.Cpu)

        override fun load(path: String, config: SessionConfig): Network = throw NeuralEngineException("cannot open $path")
    }

    private fun word(text: String, s: Long, e: Long) = Word(text, TimeRange(s, e), language = Language.Persian)
}
