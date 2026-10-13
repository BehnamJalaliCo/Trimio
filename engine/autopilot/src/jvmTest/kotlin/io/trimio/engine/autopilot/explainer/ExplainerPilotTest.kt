package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.SyntheticVoice
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.explainer.ExplainerFilm
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The audio-only pipeline end to end: listen → screenwriter → jury → the stage (a stand-in here) → sound. */
class ExplainerPilotTest {

    @Test
    fun aVoiceBecomesAFilmWithMusicUnderIt() = runBlocking {
        val words = Narrations.words(Narrations.FA_TECH, Language.Persian)
        val seconds = (words.last().range.endMs / 1000f) + 1f
        val voice = words.fold(SyntheticVoice(seconds)) { v, w -> v.word(w.range.startMs / 1000f, w.range.endMs / 1000f) }.pcm()
        var drawn: ExplainerFilm? = null
        val stage = FilmStage { film, format, fps, duration ->
            drawn = film
            assertEquals("9:16", format)
            Composition(1080, 1920, fps, duration + 0.5f, androidx.compose.ui.graphics.Color.White, Group(emptyList()))
        }
        val stages = mutableListOf<String>()
        val p = ExplainerPilot(stage).produce(ExplainerPilot.Request(Transcript(Language.Persian, words), voice, "", seed = 4)) { s, _ -> stages += s }
        p.report.take(12).forEach(::println)
        val film = assertNotNull(p.film)
        assertEquals(drawn, film)
        assertEquals(listOf("listen", "understand", "write", "draw", "sound", "done"), stages)
        assertTrue(film.chapters.size >= 4)
        assertNotNull(p.panel)
        // The narration plays whole, with the music under it, for the composition's length.
        assertEquals((p.compiled.composition.duration * voice.sampleRate).toInt(), p.soundtrack.audio.samples.size)
        assertTrue(p.plan.music != null, "music under the narration")
    }
}
