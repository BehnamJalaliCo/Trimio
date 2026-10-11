package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.roundToInt

/** Moods the Director can ask for (`music/<id>`), each a complete arrangement. */
enum class MusicMood(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    val bpm: Int,
    /** MIDI note of the key's root (bass octave is derived from it). */
    val root: Int,
    val chords: List<ProceduralMusic.Chord>,
) {
    Uplifting("uplifting", "امیدبخش", "Uplifting", 118, 60, listOf(c(0), c(7), c(9, minor = true), c(5))),
    Energetic("energetic", "پرانرژی", "Energetic", 126, 57, listOf(c(0, minor = true), c(8), c(3), c(10))),
    Chill("chill", "آرام", "Chill", 84, 62, listOf(c(0, seventh = true), c(9, minor = true, seventh = true), c(2, minor = true, seventh = true), c(7, seventh = true))),
    Cinematic("cinematic", "سینمایی", "Cinematic", 92, 50, listOf(c(0, minor = true), c(8), c(3), c(10))),
    Corporate("corporate", "رسمی", "Corporate", 108, 64, listOf(c(0), c(9, minor = true), c(5), c(7))),
    Tense("tense", "پرتنش", "Tense", 100, 52, listOf(c(0, minor = true), c(1), c(0, minor = true), c(10)));

    companion object {
        fun fromId(id: String): MusicMood? = entries.firstOrNull { it.id == id }
    }
}

private fun c(root: Int, minor: Boolean = false, seventh: Boolean = false) = ProceduralMusic.Chord(root, minor, seventh)

/** A loopable music bed and the beat grid the Director syncs cuts and elements to. */
class MusicTrack(val mood: MusicMood, val audio: PcmAudio) {
    val bpm: Int get() = mood.bpm
    val beatMs: Double get() = 60_000.0 / bpm
    val loopMs: Long get() = audio.durationMs

    /** The bed's grid: a downbeat at zero. */
    val grid: BeatGrid get() = BeatGrid(bpm.toDouble())

    /** Beat times in [0, durationMs) as the bed loops from time zero. */
    fun beats(durationMs: Long): List<Long> {
        val n = (durationMs / beatMs).toInt()
        return (0..n).map { (it * beatMs).roundToInt().toLong() }.filter { it < durationMs }
    }

    /** Downbeats (first beat of each bar): strongest points for cuts and big entrances. */
    fun downbeats(durationMs: Long): List<Long> = beats(durationMs).filterIndexed { i, _ -> i % 4 == 0 }
}

/**
 * Generative, royalty-free music beds (`music/<mood>`): an eight-bar loop of the mood's main
 * groove from [MusicComposer], folded so the loop is seamless. Deterministic per mood and seed;
 * nothing is downloaded and nothing needs a licence. Cues composed to an edit (sections, a drop,
 * an ending on a downbeat) come from [MusicComposer.compose] with an [Arranger] fit.
 */
class ProceduralMusic(private val rate: Int = 48_000) {

    data class Chord(val root: Int, val minor: Boolean = false, val seventh: Boolean = false) {
        fun tones(): List<Int> = buildList {
            add(0); add(if (minor) 3 else 4); add(7)
            if (seventh) add(if (minor) 10 else 11)
        }.map { it + root }
    }

    fun render(mood: MusicMood, seed: Int = 1): MusicTrack = MusicTrack(mood, MusicComposer(rate).loop(mood, seed, BARS))

    private companion object {
        const val BARS = 8
    }
}
