package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.style.StyleBook
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's overrides on a real production (a synthetic clip with a pause, directed by the
 * rules): every change reaches the compiled edit as it should, the same overrides always give the
 * same edit, and undoing a change gives the director's edit back.
 */
class RetouchTest {
    private val edit: Edit get() = shared
    private val base: Autopilot.Production get() = shared.base
    private val bench get() = assertNotNull(base.bench, "the production keeps its workbench")

    private fun apply(vararg changes: EditChange): Autopilot.Production =
        Retouch.apply(base, changes.fold(EditOverrides()) { o, c -> o.with(c) }).production

    private fun captions(p: Autopilot.Production) = p.compiled.beats.filter { it.recipe == "pop-captions" }.joinToString(" ") { it.text }

    private fun index(word: String) = bench.transcript.words.indexOfFirst { it.text.startsWith(word) }.also { assertTrue(it >= 0, word) }

    @Test
    fun nothingChangedIsTheDirectorsEdit() {
        assertTrue(edit.editable)
        assertTrue(Retouch.apply(base, EditOverrides()).production === base)
    }

    @Test
    fun emphasisReachesTheWordsAndTheCaptions() {
        val i = index("سیگنال")
        val o = EditOverrides().with(EditChange.Emphasise(i, i, on = true))
        val words = Retouch.words(bench, o).words
        assertEquals(1f, words[i].emphasis)
        assertTrue(words[i + 1].emphasis <= 0.5f, "neighbours step below the highlight")
        val touched = Retouch.apply(base, o).production
        assertNotEquals(textNodes(base), textNodes(touched), "the caption is drawn with the highlight on the word")
        val off = Retouch.words(bench, o.with(EditChange.Emphasise(i, i, on = false))).words
        assertEquals(0f, off[i].emphasis)
    }

    @Test
    fun editedTextIsShownAsWrittenAndOnlyWarned() {
        val i = index("بیت")
        val p = Retouch.apply(base, EditOverrides().with(EditChange.Edit(i, i, "بیتکوینز")))
        assertTrue("بیتکوینز" in captions(p.production), captions(p.production))
        assertFalse(bench.transcript.words[i].text in captions(p.production).split(' '))
        assertEquals(listOf("بیتکوینز"), p.warnings, "an unknown word is kept and warned about")
        // A two-word span becomes one: the second word is shown by the first.
        val merged = Retouch.apply(base, EditOverrides().with(EditChange.Edit(i + 1, i + 2, "۵٪"))).production
        assertTrue("۵٪" in captions(merged), captions(merged))
        assertFalse("درصد" in captions(merged).split(' '))
    }

    @Test
    fun deletedWordsAreCutFromSoundPictureAndCaptions() {
        val i = index("رشد")
        val p = apply(EditChange.Delete(i, i, on = true))
        val w = bench.transcript.words[i]
        val length = (w.range.endMs - w.range.startMs) / 1000f
        assertTrue(base.compiled.composition.duration - p.compiled.composition.duration >= length * 0.9f, "the word's time is gone")
        assertFalse("رشد" in captions(p).split(' '))
        val mid = (w.range.startMs + w.range.endMs) / 2000f
        assertTrue(p.edit.segments.none { mid in it.sourceStart..it.sourceEnd }, "no piece plays the word")
    }

    @Test
    fun aHitLandsOnItsWord() {
        val i = index("پنج")
        val p = apply(EditChange.Hit(i, on = true))
        val at = base.edit.toOutput(bench.transcript.words[i].range.startMs / 1000f)
        assertTrue(p.compiled.sfx.any { it.kind == SfxKind.Hit && abs(it.at - at) < 0.01f }, "${p.compiled.sfx}")
        assertTrue(p.soundtrack.audio.samples.contentEquals(base.soundtrack.audio.samples).not())
    }

    @Test
    fun styleSwitchChangesTheLook() {
        val other = StyleBook.all.first { it.id != base.score.look }
        val p = apply(EditChange.Style(other.id))
        assertEquals(other.id, p.score.look)
        assertEquals(other.accents.firstOrNull(), p.score.accent)
        assertEquals(other.look.canvas, p.compiled.composition.background)
    }

    @Test
    fun motionEnergySetsTheMean() {
        val calm = apply(EditChange.Energy(0.2f))
        val hot = apply(EditChange.Energy(0.95f))
        assertTrue(Retouch.meanEnergy(calm.score) < Retouch.meanEnergy(hot.score))
        assertTrue(abs(Retouch.meanEnergy(hot.score) - 0.95f) < 0.15f, "${Retouch.meanEnergy(hot.score)}")
    }

    @Test
    fun captionDensityChangesHowManyWordsEachCaptionShows() {
        val words = apply(EditChange.Captions(CaptionDensity.Word))
        val sentences = apply(EditChange.Captions(CaptionDensity.Sentence))
        assertEquals(CaptionDensity.Word.maxWords, words.score.captions.maxWords)
        val count = { p: Autopilot.Production -> p.compiled.beats.count { it.recipe == "pop-captions" } }
        assertTrue(count(words) > count(sentences), "${count(words)} vs ${count(sentences)}")
    }

    @Test
    fun musicPickAndLevelAndEffects() {
        val other = io.trimio.engine.assets.MusicMood.entries.first { it != base.plan.music }
        val picked = apply(EditChange.Music(other.id))
        assertEquals(other, picked.plan.music)
        assertNull(apply(EditChange.Music(EditOverrides.NO_MUSIC)).plan.music)
        val quiet = apply(EditChange.MusicLevel(-30f))
        val loud = apply(EditChange.MusicLevel(-8f))
        assertFalse(quiet.soundtrack.audio.samples.contentEquals(loud.soundtrack.audio.samples))
        val dry = apply(EditChange.Effects(false))
        assertTrue(dry.compiled.sfx.isEmpty())
        // The creator's own hit plays even with the accents' effects off.
        val i = index("پنج")
        assertEquals(1, apply(EditChange.Effects(false), EditChange.Hit(i, true)).compiled.sfx.size)
        // Every mix is mastered to the delivery loudness.
        assertTrue(abs(loud.soundtrack.lufs + 14.0) < 1.0, "${loud.soundtrack.lufs}")
    }

    @Test
    fun elementsTurnOffAndAddedOnesAppear() {
        val k = base.score.scenes.indexOfFirst { it.beats.isNotEmpty() }
        assertTrue(k >= 0, "the director placed graphics")
        val beat = base.score.scenes[k].beats.first()
        val off = apply(EditChange.Element(Retouch.key(k, 0), on = false))
        assertTrue(off.score.scenes.none { beat in it.beats }, "the beat is gone")
        val i = index("سیگنال")
        val added = apply(EditChange.Add(AddedElement(ElementKind.Impact, i, i + 1, "سیگنال خرید")))
        assertTrue(added.compiled.beats.any { it.recipe == "slam" && "سیگنال" in it.text }, "${added.compiled.beats}")
        val facts = EditFacts.of(edit)
        assertTrue(facts.elements.any { it.key == Retouch.key(k, 0) && it.on })
    }

    @Test
    fun pausesAndFillers() {
        val kept = apply(EditChange.Silences(false))
        assertTrue(kept.compiled.composition.duration > base.compiled.composition.duration + 0.4f, "the pause plays again")
        val fillers = Cuts.fillers(bench.transcript.words)
        assertEquals(listOf(index("خب")), fillers)
        val tidy = apply(EditChange.Fillers(true))
        assertTrue(tidy.compiled.composition.duration < base.compiled.composition.duration - 0.2f)
        assertFalse("خب" in captions(tidy).split(' '))
        val (count, seconds) = Cuts.pauses(base.edit)
        assertTrue(count >= 1 && seconds > 0.4f, "$count pauses, $seconds s")
    }

    @Test
    fun cutsMoveOntoTheBeat() {
        val music = io.trimio.engine.assets.MusicMood.Energetic
        val o = EditOverrides(music = music.id, silences = true)
        val on = Retouch.apply(base, o.copy(beatCuts = true)).production
        val grid = assertNotNull(bench.arrange(music, on.edit, Retouch.words(bench, o))).grid
        val joins = on.edit.segments.dropLast(1).map { it.outEnd }
        assertTrue(joins.isNotEmpty())
        joins.forEach { t -> assertTrue(abs(grid.nearestBeat(t.toDouble()) - t) < Retouch.BEAT_SNAP + 0.05, "cut at $t") }
    }

    @Test
    fun theSameOverridesGiveTheSameEditAndUndoGivesTheDirectorsBack() {
        val history = EditHistory()
        val i = index("سیگنال")
        history.apply(EditChange.Delete(i, i, true))
        history.apply(EditChange.Captions(CaptionDensity.Sentence))
        history.apply(EditChange.Energy(0.3f), merge = "energy")
        history.apply(EditChange.Energy(0.4f), merge = "energy")
        val a = Retouch.apply(base, history.current).production
        val b = Retouch.apply(base, history.current).production
        assertEquals(a.compiled.beats, b.compiled.beats)
        assertEquals(a.compiled.composition.duration, b.compiled.composition.duration)
        assertTrue(a.soundtrack.audio.samples.contentEquals(b.soundtrack.audio.samples))
        // The dragged slider is one step; three undos are the director's edit.
        repeat(3) { history.undo() }
        assertFalse(history.canUndo)
        assertTrue(history.current.isEmpty)
        val back = Retouch.apply(base, history.current).production
        assertEquals(base.compiled.beats, back.compiled.beats)
        assertTrue(history.canRedo)
        assertEquals(CaptionDensity.Sentence, history.redo().let { history.redo() }.captions)
    }

    @Test
    fun touchAndTheFinalRenderGoThroughTheEngine() = runBlocking {
        val i = index("رشد")
        val o = EditOverrides().with(EditChange.Delete(i, i, true))
        val touched = editor.touch(edit, o)
        assertEquals(o, touched.overrides)
        assertTrue(touched.base === edit.base)
        assertNotEquals(edit.output, touched.output, "a changed edit renders to its own file")
        assertFalse(File(touched.output).exists())
        val states = editor.retouch(edit, o).toList()
        val last = states.last()
        assertEquals(ProductionStatus.Done, last.status, "failed: ${last.error}")
        val done = assertNotNull(last.edit)
        assertTrue(File(done.output).length() > 10_000)
        // Back to nothing changed: the director's edit and its own file.
        val undone = editor.touch(done, EditOverrides())
        assertEquals(edit.output, undone.output)
        assertTrue(undone.production === edit.base)
    }

    @Test
    fun playbackDrawsTheChangedEdit() = runBlocking {
        val playback = editor.playback(edit)
        val before = assertNotNull(playback.frame(0.6f, 180))
        val other = StyleBook.all.first { it.id != base.score.look && it.look.canvas != base.compiled.composition.background }
        playback.show(editor.touch(edit, EditOverrides(style = other.id, silences = false)))
        val after = assertNotNull(playback.frame(0.6f, 180))
        val a = IntArray(before.width * before.height).also { before.readPixels(it) }
        val b = IntArray(after.width * after.height).also { after.readPixels(it) }
        assertFalse(a.contentEquals(b), "the frame shows the change")
        playback.close()
    }

    private fun textNodes(p: Autopilot.Production): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: Node) {
            when (n) {
                is Group -> n.children.forEach(::walk)
                is TextNode -> out += n.text + "|" + n.type.hashCode() + "|" + n.transform.scale
                else -> out += n::class.simpleName.orEmpty()
            }
        }
        walk(p.compiled.composition.root)
        return out
    }

    companion object {
        private val dir: File = Files.createTempDirectory("retouch-test").toFile()

        /** «سلام دوستان، خب امروز بیت‌کوین پنج درصد رشد کرد. [pause] سیگنال خرید رسید.» */
        private val said = listOf("سلام", "دوستان،", "خب", "امروز", "بیت‌کوین", "پنج", "درصد", "رشد", "کرد.", "سیگنال", "خرید", "رسید.")
        private const val PAUSE_AFTER = 8
        private const val PAUSE = 1.1f

        private fun start(i: Int) = 0.3f + i * 0.42f + if (i > PAUSE_AFTER) PAUSE else 0f

        private val recognizer = object : SpeechRecognizer {
            override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
                onProgress(1f)
                return Transcript(
                    Language.Persian,
                    said.mapIndexed { i, w -> Word(w, TimeRange((start(i) * 1000).toLong(), ((start(i) + 0.36f) * 1000).toLong()), language = Language.Persian, emphasis = 0f) },
                )
            }
        }

        /** A test pattern with a voice-like tone that falls silent in the pause. */
        private fun clip(): File {
            val file = File(dir, "clip.mp4")
            val gapFrom = start(PAUSE_AFTER) + 0.4f
            val gapTo = start(PAUSE_AFTER + 1) - 0.05f
            val p = ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=7",
                "-f", "lavfi", "-i", "sine=frequency=190:sample_rate=48000:duration=7",
                "-af", "volume=enable='between(t,$gapFrom,$gapTo)+lt(t,0.25)':volume=0,tremolo=f=5:d=0.6",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.path,
            ).redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0 && file.length() > 0) { "ffmpeg: $log" }
            return file
        }

        val editor = Productions(
            JvmProductionPlatform(File(dir, "renders"), recognizer = { recognizer }, delivery = Delivery.Draft.copy(workers = 1)),
            jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)),
        )

        val shared: Edit by lazy {
            runBlocking {
                val last = editor.produce(ProductionRequest(clip().path, prompt = "سیگنال امروز بیت‌کوین، پرانرژی", seed = 5)).toList().last()
                check(last.status == ProductionStatus.Done) { "production failed: ${last.error}" }
                last.edit!!
            }
        }
    }
}
