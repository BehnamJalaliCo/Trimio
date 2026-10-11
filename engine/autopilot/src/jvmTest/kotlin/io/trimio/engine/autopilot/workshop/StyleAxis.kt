package io.trimio.engine.autopilot.workshop

import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.style.StyleLanguage

/**
 * The workshop's style axis: each of the 28 styles ([StyleBook]) on two or three of the scenarios
 * it suits, by reusing those scenarios with the style asked for in the brief («سبک: پوستر کینتیک»,
 * "Style: Kinetic Poster"), the way a creator would ask, instead of sixty new scripts. Pairs are
 * hand-picked so each style meets the kind of piece it is for, and so a style is also tried on a
 * piece just outside its home domain. Keys are stable: the golden archive stores `id~style/seed`.
 */
internal object StyleAxis {
    val pairs: Map<String, List<String>> = linkedMapOf(
        "noir" to listOf("fa-crypto-bitcoin", "en-launch-earbuds"),
        "paper" to listOf("fa-tutorial-english", "en-tutorial-excel"),
        "lumen" to listOf("fa-mixed-ai-tools", "fa-medical-vitamin-d"),
        "bold-brutalist" to listOf("fa-flash-sale-15s", "fa-mixed-ai-tools"),
        "kinetic-poster" to listOf("fa-flash-sale-15s", "fa-motivation-story", "en-tutorial-excel"),
        "swiss-grid" to listOf("en-tutorial-excel", "fa-news-inflation"),
        "retro-vhs" to listOf("fa-tech-review-phones", "fa-motivation-story"),
        "neon-cyber" to listOf("fa-crypto-bitcoin", "fa-mixed-ai-tools"),
        "minimal-mono" to listOf("fa-medical-vitamin-d", "en-launch-earbuds"),
        "playful-pop" to listOf("fa-cooking-kuku", "fa-flash-sale-15s"),
        "finance-terminal" to listOf("fa-crypto-bitcoin", "en-news-ai"),
        "news-broadcast" to listOf("fa-news-inflation", "en-news-ai"),
        "documentary" to listOf("fa-travel-yazd", "fa-testimonial-ielts"),
        "cinematic-trailer" to listOf("en-launch-earbuds", "fa-motivation-story"),
        "beauty-soft" to listOf("fa-launch-serum", "fa-medical-vitamin-d"),
        "food-warm" to listOf("fa-cooking-kuku", "en-cooking-pasta", "fa-cafe-no-captions"),
        "tech-product" to listOf("en-launch-earbuds", "fa-tech-review-phones"),
        "education-chalk" to listOf("fa-tutorial-english", "en-tutorial-excel"),
        "sports-energy" to listOf("fa-fitness-abs", "en-testimonial-coach"),
        "kids-bright" to listOf("fa-tutorial-english", "fa-cooking-kuku"),
        "corporate-clean" to listOf("fa-testimonial-ielts", "en-tutorial-excel"),
        "luxury-gold" to listOf("fa-real-estate", "fa-launch-serum"),
        "street-hype" to listOf("fa-flash-sale-15s", "fa-fitness-abs"),
        "calm-wellness" to listOf("fa-medical-vitamin-d", "fa-motivation-story"),
        "travel-film" to listOf("fa-travel-yazd", "fa-cafe-no-captions"),
        "real-estate-premium" to listOf("fa-real-estate", "fa-cafe-no-captions"),
        "gaming" to listOf("fa-tech-review-phones", "fa-mixed-ai-tools"),
        "podcast-studio" to listOf("fa-podcast-quote", "en-news-ai"),
    )

    /** Every (scenario, style) run, in a fixed order; [only] keeps those whose scenario id starts with, or whose style is, one of its entries. */
    fun runs(only: List<String>? = null): List<Pair<Scenario, StyleLanguage>> = pairs.flatMap { (id, scenarios) ->
        val style = requireNotNull(StyleBook.of(id)) { "no style $id" }
        scenarios.map { Workshop.scenario(it) to style }
    }.filter { (s, style) -> only == null || only.any { s.id.startsWith(it) || style.id == it } }

    /** [scenario]'s brief with [style] asked for by name, in the brief's own language; the brief itself when [style] is null. */
    fun prompt(scenario: Scenario, style: String?): String {
        val s = StyleBook.of(style) ?: return scenario.prompt
        val persian = scenario.prompt.any { it in '؀'..'ۿ' }
        return scenario.prompt + if (persian) "\nسبک: ${s.nameFa}" else "\nStyle: ${s.nameEn}"
    }

    fun key(scenario: Scenario, style: String) = "${scenario.id}~$style"
}
