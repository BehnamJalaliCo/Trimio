package io.trimio.engine.styles

import io.trimio.core.model.style.BoxStyle

/**
 * Checks a pack before it can reach the renderer. A broken pack from the network must degrade to
 * "not installed", never to a crash or a garbled export.
 */
object StylePackValidator {

    data class Issue(val field: String, val message: String)

    private val idPattern = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val colorPattern = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    private val versionPattern = Regex("^\\d+\\.\\d+\\.\\d+$")

    val entries = setOf("pop", "rise", "slam", "wipe", "flip", "fade")
    val exits = setOf("fade", "fall", "shrink")
    val backgrounds = setOf("aurora", "gradient", "mesh", "grid", "waves", "solid")
    val visualizers = setOf("ring", "bars", "none")
    val cards = setOf("glass", "solid", "brutal", "outline")
    val colorRoles = setOf("accent", "accent2", "text", "emphasisText")

    fun validate(pack: StylePack): List<Issue> = buildList {
        fun check(ok: Boolean, field: String, message: String) { if (!ok) add(Issue(field, message)) }
        fun color(value: String?, field: String) { if (value != null) check(colorPattern.matches(value), field, "not a #RRGGBB(AA) colour: $value") }
        fun range(value: Float, lo: Float, hi: Float, field: String) = check(value in lo..hi, field, "$value outside $lo..$hi")

        check(pack.schema <= StylePack.SUPPORTED_SCHEMA, "schema", "schema ${pack.schema} is newer than supported ${StylePack.SUPPORTED_SCHEMA}")
        check(idPattern.matches(pack.id), "id", "must be lowercase-kebab-case")
        check(pack.spec.id == pack.id, "spec.id", "must equal the pack id")
        check(versionPattern.matches(pack.version), "version", "must be MAJOR.MINOR.PATCH")
        check(pack.nameFa.isNotBlank() && pack.nameEn.isNotBlank(), "name", "both Persian and English names are required")

        val p = pack.spec.palette
        check(p.background.isNotEmpty(), "palette.background", "needs at least one colour")
        p.background.forEachIndexed { i, c -> color(c, "palette.background[$i]") }
        color(p.text, "palette.text"); color(p.accent, "palette.accent"); color(p.accent2, "palette.accent2")
        color(p.emphasisText, "palette.emphasisText"); color(p.shadow, "palette.shadow")

        val c = pack.spec.captions
        range(c.size, 0.03f, 0.3f, "captions.size")
        check(c.weight in 100..900, "captions.weight", "must be 100..900")
        check(c.maxWordsPerLine in 1..8, "captions.maxWordsPerLine", "must be 1..8")
        check(c.entry in entries, "captions.entry", "unknown entry preset ${c.entry}")
        check(c.exit in exits, "captions.exit", "unknown exit preset ${c.exit}")
        color(c.boxColor, "captions.boxColor"); color(c.strokeColor, "captions.strokeColor")
        range(c.strokeWidth, 0f, 0.03f, "captions.strokeWidth")
        range(c.shadowBlur, 0f, 0.05f, "captions.shadowBlur")
        range(c.emphasis.scale, 1f, 2f, "captions.emphasis.scale")
        range(c.emphasis.threshold, 0f, 1f, "captions.emphasis.threshold")
        check(c.emphasis.colorRole in colorRoles, "captions.emphasis.colorRole", "unknown role ${c.emphasis.colorRole}")
        check(c.emphasis.box == BoxStyle.None || c.emphasis.box == BoxStyle.Highlight, "captions.emphasis.box", "emphasis supports None or Highlight")

        val bg = pack.spec.background.preset
        val shader = bg.removePrefix("shader:")
        check(bg in backgrounds || (bg.startsWith("shader:") && shader in pack.spec.shaders), "background.preset", "unknown background $bg")
        pack.spec.shaders.forEach { (name, src) ->
            check(name.matches(idPattern), "shaders.$name", "shader names must be kebab-case")
            check("half4 main(" in src && src.length < 32_000, "shaders.$name", "must define half4 main(float2) and stay under 32 KB")
        }

        range(pack.spec.overlay.grain, 0f, 0.2f, "overlay.grain")
        range(pack.spec.overlay.vignette, 0f, 1f, "overlay.vignette")
        range(pack.spec.overlay.letterbox, 0f, 0.2f, "overlay.letterbox")
        check(pack.spec.elements.card in cards, "elements.card", "unknown card ${pack.spec.elements.card}")
        range(pack.spec.motion.energy, 0f, 1f, "motion.energy")
        range(pack.spec.audioOnly.captionScale, 0.5f, 2f, "audioOnly.captionScale")
        check(pack.spec.audioOnly.visualizer in visualizers, "audioOnly.visualizer", "unknown visualizer ${pack.spec.audioOnly.visualizer}")
    }
}
