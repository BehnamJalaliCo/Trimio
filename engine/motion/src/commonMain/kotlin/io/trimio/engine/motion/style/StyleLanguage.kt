package io.trimio.engine.motion.style

import androidx.compose.ui.graphics.Color
import io.trimio.core.model.style.DesignStyle
import io.trimio.engine.motion.Contrast
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.recipe.Look

/**
 * One of the 28 styles as a design language the director speaks, not a template it fills: the
 * same rules the planner always follows, weighted by the style's taste. A style never places a
 * graphic or writes a word; it says which text entrances, transitions, camera moves, emphasis
 * marks, caption lengths, stage backgrounds and how much full-frame graphics it prefers, and how
 * everything looks ([look]: palette, type voices, texture, footage grade, the easing scenes settle
 * with). The craft rules (timing, layout, the contrast floor, readable sizes) stay the compiler's
 * and the jury's, so every style keeps them.
 *
 * Hooks for the other layers: [background] names a shader family ([io.trimio.engine.motion.ShaderFamily])
 * and [lens] a distortion, both carried by the [look]; [sound] is a mood tag the audio layer maps
 * to music and an SFX palette (`SoundPresets.forTag`).
 */
data class StyleLanguage(
    /** Stable id: the score's `look` ("noir", "retro-vhs"). The first three are the original looks. */
    val id: String,
    val nameFa: String,
    val nameEn: String,
    /**
     * The entry of the app's style picker ([DesignStyle] id) that asks for this language: each of
     * the 28 picker styles maps to exactly one language.
     */
    val pack: String,
    /**
     * The plain look words this style answers (see `RulesUnderstander.brief`): "noir" for dark,
     * "paper" for light or minimal, "lumen" for calm or luxurious.
     */
    val tones: Set<String>,
    /** Style words (Persian and English) a brief may use to ask for it; its names always count, and count more. */
    val words: List<String>,
    /** Palette, type voices (weights, line height, tracking, Latin case, Persian/Latin pairing by role) and texture. */
    val look: Look,
    /** Accent variants that keep the style's character; the first is the look's own. */
    val accents: List<String>,
    val motion: MotionGrammar,
    val captions: CaptionTreatment,
    val rhythm: Cadence,
    /** Music and SFX mood tag (one of the audio layer's preset tags). */
    val sound: String,
    val fit: Fit,
) {
    /** The shader family its graphic stages are drawn with, or null for the drifting aurora. */
    val background: String? get() = look.backdrop

    /** A distortion over the whole piece ("halation", "chromatic"), or null. */
    val lens: String? get() = look.lens

    /** Whether a brief's plain look word ("noir", "paper", "lumen") is answered by this style. */
    fun answers(tone: String): Boolean = tone == id || tone in tones

    /** How well the style suits a piece about [domain] in [mood], 0..1. */
    fun fitness(domain: String, mood: String): Float = (fit.domains[domain] ?: OFF_DOMAIN) * (fit.moods[mood] ?: OFF_MOOD)

    private companion object {
        const val OFF_DOMAIN = 0.05f
        const val OFF_MOOD = 0.35f
    }
}

/**
 * How a style moves. Weights multiply the planner's own options (which already follow energy and
 * mood), so a style shifts the odds without ever forcing a move the moment does not allow; with
 * no weights listed the planner's choice is untouched.
 */
data class MotionGrammar(
    /** The energy beats lean toward (0 calm … 1 explosive); negative leaves the brief's alone. */
    val energy: Float,
    /** The easing family scene moves settle with. */
    val ease: Ease,
    /** Text entrances (recipe names: "mask-rise", "slam", "blur-in", …) and how much it likes them. */
    val entrances: Map<String, Float>,
    /** Transitions into a full-frame graphic ("cut", "whip", "zoom", "flash", "leak", "glitch", "ripple"). */
    val enter: Map<String, Float>,
    /** Transitions back to the speaker: the exit vocabulary of its graphic scenes. */
    val exit: Map<String, Float>,
    /** Camera habit: how often an intense line gets a push-in, and a calm one a locked-off frame (1 = neutral). */
    val push: Float,
    val still: Float,
) {
    fun entrance(recipe: String): Float = weightIn(entrances, recipe)
    fun into(transition: String): Float = weightIn(enter, transition)
    fun outOf(transition: String): Float = weightIn(exit, transition)

    private fun weightIn(m: Map<String, Float>, key: String) = if (m.isEmpty()) 1f else m[key] ?: UNLISTED

    companion object {
        /** A move the style does not list stays possible, just rarer. */
        const val UNLISTED = 0.55f
    }
}

/** Easing families, as the curves scene moves settle with. */
enum class Ease(val curve: Easing) {
    Expo(Easing.ExpoOut),
    Snap(Easing.QuintOut),
    Spring(Easing.Land),
    Back(Easing.BackOut(1.4f)),
    Sine(Easing.SineInOut),
}

/** How spoken captions are treated: the emphasis marks it likes, words per caption, where they sit. */
data class CaptionTreatment(val marks: Map<String, Float>, val words: List<Int>, val place: String = "lower")

/**
 * Density and rhythm, relative to the planner's defaults: [density] scales how often a picture or
 * fill joins the speaker (and so the longest gap without a visual event), [takeover] the share of
 * the piece given to full-frame graphics, [stages] the backgrounds of those ("aurora", "grid", "plain").
 */
data class Cadence(val density: Float, val takeover: Float, val stages: Map<String, Float>)

/** Where a style belongs: weights by understanding domain and mood (missing ones count little). */
data class Fit(val domains: Map<String, Float>, val moods: Map<String, Float>)

/**
 * The 28 languages ([Styles.all]) and how a brief reaches them: by a style word, its name, or the
 * picker style it answers ([asked]); [check] holds every style to the craft floors.
 */
object StyleBook {
    val all: List<StyleLanguage> get() = Styles.all

    private val byId by lazy { all.associateBy { it.id } }

    fun of(id: String?): StyleLanguage? = id?.trim()?.lowercase()?.let { byId[it] }

    /**
     * The styles [brief] asks for by name or style word, best match first (ties kept, the planner
     * breaks them by fit): a name, id or picker style counts [NAMED], a style word [WORD]. Empty
     * when the brief names no style.
     */
    fun asked(brief: String): List<StyleLanguage> {
        val said = tokens(brief)
        if (said.isEmpty()) return emptyList()
        val scored = all.map { s -> s to names(s).sumOf { if (said.has(it)) NAMED else 0 } + s.words.sumOf { if (said.has(tokens(it))) WORD else 0 } }
        val best = scored.maxOf { it.second }
        return if (best == 0) emptyList() else scored.filter { it.second == best }.map { it.first }
    }

    /** Names that ask for [s] outright: its id, both names, its picker style's id and names. */
    private fun names(s: StyleLanguage): List<List<String>> {
        val pack = DesignStyle.fromId(s.pack)
        return listOfNotNull(s.id, s.nameFa, s.nameEn, s.pack, pack?.nameFa, pack?.nameEn).map(::tokens).filter { it.isNotEmpty() }
    }

    /** Lower case, Persian letter forms, half-spaces and hamza marks joined, split on anything not a letter or digit. */
    private fun tokens(s: String): List<String> {
        val t = s.lowercase().replace("‌", "").replace("ٔ", "").replace('ي', 'ی').replace('ك', 'ک')
        val out = mutableListOf<String>()
        val b = StringBuilder()
        for (c in t) if (c.isLetterOrDigit()) b.append(c) else if (b.isNotEmpty()) { out += b.toString(); b.clear() }
        if (b.isNotEmpty()) out += b.toString()
        return out
    }

    /** [phrase] said as consecutive words; a word may carry a short suffix («نئونی», "glasses"). */
    private fun List<String>.has(phrase: List<String>): Boolean = phrase.isNotEmpty() &&
        (0..size - phrase.size).any { i -> phrase.indices.all { j -> same(this[i + j], phrase[j]) } }

    private fun same(said: String, word: String) = said == word || (word.length >= MIN_STEM && said.startsWith(word) && said.length - word.length <= SUFFIX)

    /**
     * What breaks the craft floors in [s] (empty when nothing does): contrast of every text role
     * against the canvas, the stages and the cards, for every accent variant; readable type
     * (headlines and captions heavy and untracked, so Persian joins hold); and a vocabulary the
     * planner and compiler know.
     */
    fun check(s: StyleLanguage): List<String> = buildList {
        val l = s.look
        fun ratio(a: Color, b: Color) = Contrast.ratio(Contrast.luminance(a), Contrast.luminance(b))
        fun need(ok: Boolean, what: String) { if (!ok) add("${s.id}: $what") }
        need(l.name == s.id, "look named ${l.name}")
        need(s.tones.isNotEmpty() && s.tones.all { it in TONES }, "tones ${s.tones}")
        need(ratio(l.ink, l.canvas) >= INK_FLOOR, "ink on canvas ${ratio(l.ink, l.canvas)}")
        need(ratio(l.muted, l.canvas) >= MUTED_FLOOR, "muted on canvas ${ratio(l.muted, l.canvas)}")
        need(ratio(l.positive, l.canvas) >= FIGURE_FLOOR && ratio(l.negative, l.canvas) >= FIGURE_FLOOR, "up/down colours on canvas")
        need(ratio(l.hot, l.canvas) >= HOT_FLOOR, "hot on canvas ${ratio(l.hot, l.canvas)}")
        need(ratio(l.ink, over(l.card, l.canvas)) >= TEXT_FLOOR, "ink on cards")
        l.aurora.forEach { need(ratio(l.ink, it) >= TEXT_FLOOR, "ink on its aurora") }
        need(s.accents.firstOrNull()?.let { l.withAccent(it).accent == l.accent } == true, "first accent is not the look's own")
        for (hex in s.accents) {
            val v = l.withAccent(hex)
            need(v.accent != l.accent || hex == s.accents.first(), "accent $hex does not parse")
            need(ratio(v.onAccent, v.accent) >= TEXT_FLOOR, "text on accent $hex")
            stage(v).forEach { need(ratio(v.ink, it) >= TEXT_FLOOR, "ink on the stage lit by $hex") }
            if ("ink" in s.captions.marks) need(ratio(v.accent, v.canvas) >= FIGURE_FLOOR, "ink mark $hex on canvas")
        }
        for ((role, v) in listOf("headline" to l.headline, "caption" to l.caption)) {
            need(v.weight >= HEAVY && v.tracking == 0f && v.lineHeight in LINE_HEIGHTS, "$role voice ${v.weight}/${v.tracking}/${v.lineHeight}")
        }
        need(l.body.weight >= BODY && l.label.weight >= BODY && l.number.weight >= BODY, "body, label or number too light")
        need(s.motion.entrances.keys.all { it in ENTRANCES }, "entrances ${s.motion.entrances.keys - ENTRANCES}")
        need((s.motion.enter.keys + s.motion.exit.keys).all { it in TRANSITIONS }, "transitions")
        need(s.captions.marks.isNotEmpty() && s.captions.marks.keys.all { it in MARKS }, "marks ${s.captions.marks.keys}")
        need(s.captions.words.isNotEmpty() && s.captions.words.all { it in CAPTION_WORDS }, "caption words ${s.captions.words}")
        need(s.rhythm.stages.isNotEmpty() && s.rhythm.stages.keys.all { it in STAGES }, "stages ${s.rhythm.stages.keys}")
        need(s.rhythm.density in RHYTHM && s.rhythm.takeover in RHYTHM, "density/takeover out of range")
        need(s.motion.energy < 0f || s.motion.energy in 0f..1f, "energy ${s.motion.energy}")
        need(s.fit.domains.isNotEmpty() && s.fit.moods.isNotEmpty(), "fits nothing")
    }

    /** The compiler's stage for a graphic scene over footage: the canvas lit by the accent, heat and up colours. */
    private fun stage(l: Look): List<Color> {
        val dark = Contrast.luminance(l.canvas) < 0.5f
        fun mix(c: Color, t: Float) = Color(
            l.canvas.red + (c.red - l.canvas.red) * t, l.canvas.green + (c.green - l.canvas.green) * t, l.canvas.blue + (c.blue - l.canvas.blue) * t,
        )
        return listOf(mix(l.accent, if (dark) 0.2f else 0.35f), mix(l.hot, if (dark) 0.16f else 0.22f), mix(l.positive, if (dark) 0.1f else 0.15f))
    }

    private fun over(top: Color, under: Color) = Color(
        top.red * top.alpha + under.red * (1f - top.alpha), top.green * top.alpha + under.green * (1f - top.alpha), top.blue * top.alpha + under.blue * (1f - top.alpha),
    )

    private const val NAMED = 3
    private const val WORD = 1
    private const val MIN_STEM = 3
    private const val SUFFIX = 2
    private const val INK_FLOOR = 7f
    private const val TEXT_FLOOR = 4.5f
    private const val MUTED_FLOOR = 4f
    private const val FIGURE_FLOOR = 3f
    private const val HOT_FLOOR = 2.5f
    private const val HEAVY = 700
    private const val BODY = 400
    private val LINE_HEIGHTS = 0.9f..1.45f
    private val CAPTION_WORDS = 2..6
    private val RHYTHM = 0.6f..1.4f
    val TONES = setOf("noir", "paper", "lumen")
    val ENTRANCES = setOf("mask-rise", "slam", "stack", "spread", "flip", "type-on", "blur-in", "glitch")
    val TRANSITIONS = setOf("cut", "whip", "zoom", "flash", "leak", "glitch", "ripple") + io.trimio.engine.motion.recipe.Transitions.KINDS
    val MARKS = setOf("block", "ink", "underline", "circle") + io.trimio.engine.motion.recipe.Craft.HAND_MARKS
    val STAGES = setOf("aurora", "grid", "plain")
}
