package io.trimio.engine.autopilot

import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.llm.StopReason
import io.trimio.engine.motion.score.Subject
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The eyes' head box fused with the pixel finder's, with a fake seeing model (no model runs). */
class SubjectGroundingTest {
    /** Answers each frame with the next of [replies]; counts what it was shown. */
    private class FakeEyes(private val replies: List<String>, override val canSee: Boolean = true) : LanguageModel {
        override val id = "fake-eyes"
        override val isLocal = true
        var asked = 0
        var images = 0

        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            images += request.messages.sumOf { it.images.size }
            val text = replies[asked++ % replies.size]
            return Generation(text, StopReason.EndTurn, id)
        }
    }

    private val frames = List(5) { RgbImage(4, 8, ByteArray(4 * 8 * 3)) }
    private val pixels = SubjectFinder.Found(Subject(0.30f, 0.20f, 0.66f, 0.42f), confidence = 0.8f)

    private fun close(a: Subject, b: Subject) = listOf(a.left - b.left, a.top - b.top, a.right - b.right, a.bottom - b.bottom).all { kotlin.math.abs(it) < 0.011f }

    @Test
    fun agreementIsTrustedAsTheAverage() = runBlocking {
        val eyes = FakeEyes(listOf("""{"person":"yes","head":[320,210,680,430]}""", """{"person":"yes","head":[310,200,670,420]}""", """{"person":"yes","head":[330,220,690,440]}"""))
        val s = assertNotNull(SubjectGrounding(eyes).ground(frames, pixels))
        assertEquals(3, eyes.asked, "three frames are enough")
        assertEquals(3, eyes.images)
        assertTrue(close(s, Subject(0.31f, 0.205f, 0.67f, 0.425f)), "$s")
    }

    @Test
    fun disagreementTakesThePlausibleUnion() = runBlocking {
        // The pixels stopped at the eyebrows (hair missed); the eyes include the hair.
        val eyes = FakeEyes(listOf("""{"person":"yes","head":[300,120,660,330]}"""))
        val s = assertNotNull(SubjectGrounding(eyes).ground(frames, pixels.copy(subject = Subject(0.30f, 0.26f, 0.66f, 0.42f))))
        assertTrue(close(s, Subject(0.30f, 0.12f, 0.66f, 0.42f)), "$s")
    }

    @Test
    fun anImplausibleUnionKeepsTheSurerSide() = runBlocking {
        // The eyes boxed a poster across the frame from the speaker: the union would be most of the frame.
        val eyes = FakeEyes(listOf("""{"person":"yes","head":[650,600,990,990]}"""))
        val s = assertNotNull(SubjectGrounding(eyes).ground(frames, pixels))
        assertEquals(pixels.subject, s)
    }

    @Test
    fun theEyesReplaceThePrior() = runBlocking {
        val eyes = FakeEyes(listOf("""{"person":"yes","head":[100,150,420,380]}"""))
        val prior = SubjectFinder.Found(SubjectFinder.PRIOR, 0.1f, prior = true)
        val s = assertNotNull(SubjectGrounding(eyes).ground(frames, prior))
        assertTrue(close(s, Subject(0.10f, 0.15f, 0.42f, 0.38f)), "$s")
    }

    @Test
    fun nothingUsableFromTheEyesKeepsThePixels() = runBlocking {
        for (reply in listOf("""{"person":"no","head":[]}""", """{"person":"yes","head":[0,0,1000,1000]}""", """{"person":"yes","head":[500,500,400,600]}""", "not json")) {
            val s = SubjectGrounding(FakeEyes(listOf(reply))).ground(frames, pixels)
            assertEquals(pixels.subject, s, reply)
        }
        // A model that cannot see is not asked at all.
        val blind = FakeEyes(listOf("""{"person":"yes","head":[100,150,420,380]}"""), canSee = false)
        assertEquals(pixels.subject, SubjectGrounding(blind).ground(frames, pixels))
        assertEquals(0, blind.asked)
    }

    @Test
    fun boxesAreReadAsThousandths() {
        assertEquals(Subject(0.25f, 0.1f, 0.75f, 0.4f), SubjectGrounding.box(SubjectGrounding.Seen("yes", listOf(250, 100, 750, 400))))
        assertEquals(null, SubjectGrounding.box(SubjectGrounding.Seen("yes", listOf(250, 100, 750, 1400))))
    }
}
