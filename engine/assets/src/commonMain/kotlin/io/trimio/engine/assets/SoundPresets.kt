package io.trimio.engine.assets

/**
 * A style's sound: the music moods it may play (first is preferred) and the SFX palette its cues
 * are voiced with. The contract with the style catalogue: a style's language names one mood tag
 * (`StyleLanguage.sound`, one of [SoundPresets.TAGS]); the director asks [SoundPresets.forTag]
 * and gets music and effects that belong together. Unknown tags fall back by keyword, then to null
 * (the planner's own choice from the brief's mood).
 */
data class SoundPreset(
    val tag: String,
    /** Allowed moods, best first; the brief's energy picks among them. */
    val moods: List<MusicMood>,
    val palette: SfxPalette,
) {
    val music: MusicMood get() = moods.first()
}

object SoundPresets {
    private val all = listOf(
        SoundPreset("punchy", listOf(MusicMood.Energetic, MusicMood.Uplifting), SfxPalette.Punchy),
        SoundPreset("hype", listOf(MusicMood.Energetic, MusicMood.Tense), SfxPalette.Punchy),
        SoundPreset("cinematic", listOf(MusicMood.Cinematic, MusicMood.Tense), SfxPalette.Cinematic),
        SoundPreset("epic", listOf(MusicMood.Cinematic, MusicMood.Energetic), SfxPalette.Cinematic),
        SoundPreset("luxury", listOf(MusicMood.Cinematic, MusicMood.Chill), SfxPalette.Luxe),
        SoundPreset("elegant", listOf(MusicMood.Chill, MusicMood.Corporate), SfxPalette.Luxe),
        SoundPreset("minimal", listOf(MusicMood.Corporate, MusicMood.Chill), SfxPalette.Luxe),
        SoundPreset("corporate", listOf(MusicMood.Corporate, MusicMood.Uplifting), SfxPalette.Soft),
        SoundPreset("warm", listOf(MusicMood.Chill, MusicMood.Uplifting), SfxPalette.Soft),
        SoundPreset("lofi", listOf(MusicMood.Chill), SfxPalette.Soft),
        SoundPreset("organic", listOf(MusicMood.Chill, MusicMood.Uplifting), SfxPalette.Soft),
        SoundPreset("ambient", listOf(MusicMood.Chill, MusicMood.Cinematic), SfxPalette.Soft),
        SoundPreset("tech", listOf(MusicMood.Energetic, MusicMood.Corporate), SfxPalette.Digital),
        SoundPreset("glitch", listOf(MusicMood.Tense, MusicMood.Energetic), SfxPalette.Digital),
        SoundPreset("retro", listOf(MusicMood.Energetic, MusicMood.Uplifting), SfxPalette.Digital),
        SoundPreset("dark", listOf(MusicMood.Tense, MusicMood.Cinematic), SfxPalette.Cinematic),
        SoundPreset("playful", listOf(MusicMood.Uplifting, MusicMood.Energetic), SfxPalette.Playful),
        SoundPreset("bright", listOf(MusicMood.Uplifting, MusicMood.Corporate), SfxPalette.Playful),
    )

    /** The mood tags a style may name. */
    val TAGS: List<String> = all.map { it.tag }

    /** Keywords that land an unknown tag on a known one («آرام» → warm, "neon" → tech). */
    private val ALIASES = mapOf(
        "calm" to "warm", "chill" to "lofi", "soft" to "warm", "natural" to "organic", "bio" to "organic",
        "premium" to "luxury", "gold" to "luxury", "chrome" to "luxury", "neon" to "tech", "cyber" to "glitch", "digital" to "tech",
        "futur" to "tech", "bold" to "punchy", "brutal" to "punchy", "street" to "hype", "energ" to "hype", "trailer" to "epic",
        "fun" to "playful", "kids" to "playful", "clay" to "playful", "pop" to "bright", "noir" to "dark", "grain" to "retro",
        "vapor" to "retro", "y2k" to "retro", "clean" to "minimal", "flat" to "minimal", "editorial" to "elegant",
        "آرام" to "warm", "لوکس" to "luxury", "سینمایی" to "cinematic", "پرانرژی" to "hype", "شاد" to "playful", "تکنو" to "tech",
    )

    fun forTag(tag: String?): SoundPreset? {
        val t = tag?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        all.firstOrNull { it.tag == t }?.let { return it }
        val alias = ALIASES.entries.firstOrNull { (k, _) -> k in t }?.value ?: return null
        return all.first { it.tag == alias }
    }

    /** The palette that suits a mood when no style names one. */
    fun paletteFor(mood: MusicMood?): SfxPalette = when (mood) {
        MusicMood.Energetic -> SfxPalette.Punchy
        MusicMood.Cinematic -> SfxPalette.Cinematic
        MusicMood.Chill -> SfxPalette.Soft
        MusicMood.Corporate -> SfxPalette.Luxe
        MusicMood.Tense -> SfxPalette.Digital
        MusicMood.Uplifting -> SfxPalette.Playful
        null -> SfxPalette.Soft
    }
}
