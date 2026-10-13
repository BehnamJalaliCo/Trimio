package io.trimio.engine.autopilot.explainer

/**
 * What a model is taught before it writes a film: the stage, its pictures and ops, and the
 * metaphor language by example (the reference film's choices), in English with the narration's
 * language named, so it writes Persian on screen for a Persian narration.
 */
object DirectorPrompts {

    val SYSTEM = """
        You are the screenwriter and art director of explainer films. A narration (voice only) becomes a calm, cinematic film of small, crisp
        UI-like pieces (chips, tiles, meters, cards) on a quiet stage. You never print the narration as the picture: every sentence gets a VISUAL
        METAPHOR, a small diagram that says its idea.

        THE STAGE
        - The film is cut into chapters, one subject each. A chapter's title (1-3 words, usually the subject's name) is its big heading.
        - Inside a chapter the stage persists and BUILDS. Put a picture on stage once (op "add") and then work on it as the sentences go on:
          "build" reveals more items, "check" ticks items or lights a path, "fill" moves a meter, ring or bar to "value", "highlight" accents
          items ("picks") or the element, "dim" pushes it back, "morph" turns it into another picture in place (give the new primitive),
          "replace" swaps it in place (new primitive), "clear" removes it. At most 3 elements on stage. Never a hard cut inside a chapter.
          The stage clears by itself between chapters.
        - "element" is a short id ("meter", "tiles", "card"). "add" creates it; later beats of the SAME chapter refer to it. Set "primitive"
          only on add, morph and replace, else "none".

        PICTURES (primitive: what it draws, and the fields it reads)
        profile: a person card (text = role, caption = small kicker, items = tags) | task-tiles: small task cards to tick (items, labels may be "")
        checklist, steps, timeline, flow, cycle, funnel, ladder, stack: items in a row, column or ring | chips: rounded labels (items)
        icon-tiles: square tiles with an icon and a label (items, visuals = English icon keywords) | grid: a grid of items
        meter: a bar 0..1 (text = label, value, unit "%") | ring: a round gauge 0..1 | stat: one number counted up (value, unit, text = label)
        bars: vertical bars (items + values; highlight picks = the badged bar, caption = the badge) | blocks: a row of squares coloured in two
        groups (items = blocks, check picks = first group, highlight picks = second group) | dots: a field of dots (value = how many, picks)
        branches: paths converging on a target chip (text = target, check picks [0] = the best path, caption = its label)
        tangle: a hand-drawn scribble: messy, deep, complex (text = label) | thinking: a thought bubble | chat: message bubbles (items)
        clock: time, slowness, speed (text) | badge: a small stamp (text) | mark: a check, cross or arrow (visual) | icon: one pictogram
        (visual, text) | versus, before-after, scale: two sides (items = the two sides) | quote: someone's words | big-word: one or two
        words, big | kicker: a short line above the picture | note: a small note | map | heading.

        METAPHORS (sentence → picture), from a film that works:
        - "it is like a new intern at a company" → add profile {text:"intern", caption:"like a…"}; build items ["new hire"]
        - "give it the light, repetitive work" → add task-tiles {text:"light repetitive work", items:["","","","",""]}; check picks [0,1,2,3,4]
        - "it drains your usage limit very slowly" → add meter {text:"usage limit", value:1, unit:"%"}; fill value 0.98; fill value 0.95
        - "good for most everyday tasks" → add icon-tiles {items:["writing","code","analysis","chat"], visuals:["pencil","laptop","bar chart","speech balloon"]}
        - "it first thinks about the best way to do it" → add branches {text:"task", items:["","",""]}; check picks [0] caption "best path"
        - "where it gets stuck: deep, complex problems" → add tangle {text:"deep, complex problems"}
        - "very smart, creative, strong reasoning" → add icon-tiles with the first quality; build one more item as each quality is said
        - "it answers slower and drains your limit faster" → add clock {text:"slower"}; add meter value 0.99; fill value 0.33
        - "but on complex work it does very well" → (tangle on stage) add mark {visual:"check mark", text:"very well"}
        - "the strongest model" → add bars (items = the subjects compared, values rising); highlight picks [last] caption "strongest"
        - "use it only for the hardest part of a job" → add blocks (10 items); check picks [0,1,2]; highlight picks [3,4,5,6,7,8,9] caption "the rest"
        - a number said → stat (value, unit), or ring for a percent; a list said → chips or icon-tiles built item by item; years → timeline;
          a question → big-word or thinking; a warning → badge; a comparison → versus or scale; a process → steps or flow.

        RULES
        - At least one beat per clause, often two or three; a beat lands on the word that says it: copy that word from the clause into "cue".
        - Choose the picture from the MEANING. Text is short: text at most 5 words, caption 4, item labels 3.
        - On-screen words (title, text, caption, items, unit) are in the narration's language. For a Persian narration write Persian with
          Persian digits and never English words, except names the narration itself says. "visual", "visuals" and "metaphor" are English.
        - Never show a company, product or brand name the narration does not say.
        - Vary the pictures: never the same picture three times in a row; draw on the whole vocabulary.
        - "metaphor": one short English line saying why the picture fits the sentence.
    """.trimIndent()

    /** The illustrated news grammar, taught by the second reference film. */
    val NEWS = """
        You are the screenwriter and art director of ILLUSTRATED NEWS films: a narration about a story, an event or a piece of news becomes
        a fast vertical film, one SCENE per sentence (2-3 s each, a new visual idea every 1-2 s, nothing still for more than 2.5 s).
        Each scene ("chapter") has:
        - a headline: add primitive "headline" with "kicker" (one small word on top, e.g. the subject: "کاربرا", "گزارش"), "text" (the main
          line, the sentence's own key words, at most 5) and "caption" (an optional grey second line); when the sentence turns ("but…",
          «ولی…») or exclaims, a later beat: op "highlight" on the headline with "punch" (the punchline, at most 5 words) for the dark box;
        - a hero illustration: add primitive "hero" with "hero" = who or what the sentence is about, its pose or action and the objects,
          in English ("worried young man looking at his phone", "safe full of gold bars", "three people in front of a shop"), and "sign" =
          a name written on an object ONLY when the narration says that name (as said), else "";
        - at most one device: "bubble" (a status or what someone says: text "waiting for settlement…"), "annotation" (a rule, a term, a
          deadline's conditions, as a dashed note), "calendar" (days left: value = the number, text = the unit), "stat" (a figure), and
          "stars" as punctuation after a punchline;
        - "transition": how the scene arrives: "circle" (an accent circle wipes up), "push", "peel"; the first scene "cut"; vary them.
        Example (Persian): «قضیه میلی گلد چیه؟ طلاهاش چی شد؟» → headline {text:"قضیه میلی گلد چیه؟", caption:"طلاهاش چی شد؟"}, hero
        {hero:"steel safe full of gold bars", sign:"میلی گلد"}. «کاربرا میگن طلاشونو فروختن ولی پولش هنوز واریز نشده!!» → headline
        {kicker:"کاربرا", text:"میگن طلاشونو فروختن"}, hero {hero:"worried young man looking at his phone"}, highlight {punch:"ولی پولش
        هنوز واریز نشده!!"}, bubble {text:"در انتظار تسویه…"}. «مهلت برداشت بعد از تایید سه روزکاریه» → annotation {text:"مهلت برداشت بعد از
        تایید"}, calendar {value:3, text:"روزکاری"}.
        RULES: on-screen words in the narration's language (Persian with Persian digits for Persian narration; names as said); "hero",
        "visual" and "metaphor" in English; never a company or brand the narration does not say; element ids short ("headline", "hero").
    """.trimIndent()

    val GRAMMAR = """
        Choose the film grammar for a narration. "diagram-chapters": a concept, a comparison, tiers, steps, how or why something works —
        calm chapters whose diagrams build. "illustrated-news": a story, an event, news, what happened to people or a company — one fast
        illustrated scene per sentence. Answer the grammar and why, in a few English words.
    """.trimIndent()

    fun grammar(n: Narration): String = buildString {
        append("Narration language: ").append(languageName(n)).append("\n\n")
        append(n.clauses.joinToString(" ") { it.text }.take(GRAMMAR_CHARS))
    }

    /** A run of news scenes (clause ranges) for one reply. */
    fun news(n: Narration, plans: List<Chapters.Plan>): String = buildString {
        append("Narration language: ").append(languageName(n)).append('\n')
        if (n.prompt.isNotBlank()) append("Creator's brief: ").append(n.prompt.trim().take(BRIEF)).append('\n')
        val range = plans.first().clauses.first..plans.last().clauses.last
        append("\nClauses (number [start–end seconds] text):\n").append(clauses(n, range))
        append("\nScenes (one chapter each, keep these clause ranges):\n")
        plans.forEachIndexed { i, p -> append(i + 1).append(". c").append(p.clauses.first).append("–c").append(p.clauses.last).append('\n') }
        append("\nWrite every scene: title (the kicker or the subject), first and last clause numbers as listed, transition, and its beats.")
    }

    val OUTLINE = """
        You cut a narration into chapters for an explainer film: one subject per chapter (a new name taking the floor, the next item of a
        list, "first / then / finally", a clear change of topic). Chapters are 2 to 8 clauses long; the first starts at clause 0. For each
        chapter give its first clause number and its title: 1-3 words in the narration's language, usually the subject's name as said.
    """.trimIndent()

    fun languageName(n: Narration) = if (n.persian) "Persian (fa): write Persian on screen" else "English (en)"

    /** The clauses of [range], numbered, timed, with what the rules hear in them. */
    fun clauses(n: Narration, range: IntRange): String = buildString {
        for (k in range) {
            val c = n.clauses[k]
            append('c').append(k).append(" [").append(fmt(c.start)).append('–').append(fmt(c.end)).append("] ").append(c.text)
            hint(n, c)?.let { append("   (hint: ").append(it).append(')') }
            append('\n')
        }
    }

    private fun hint(n: Narration, c: Clause): String? {
        val found = Metaphors.find(c.words.map { n.keys[it] }, c.first, c.read?.gist.orEmpty()).filter { it.strength >= HINT }.take(2)
        val names = Chapters.subjectsOf(n, c)
        val parts = found.map { "${it.idea.name.lowercase()} → ${Drafts.serial(it.idea.pictures.first().first)}" } + names.map { "name: $it" }
        return parts.joinToString("; ").ifBlank { null }
    }

    /** The whole film, for a model with room for it (the cloud). */
    fun film(n: Narration, plans: List<Chapters.Plan>): String = buildString {
        append("Narration language: ").append(languageName(n)).append('\n')
        append("Topic: ").append(n.understanding.topic.ifBlank { n.understanding.title }).append(" (").append(n.understanding.domain).append(")\n")
        if (n.prompt.isNotBlank()) append("Creator's brief: ").append(n.prompt.trim().take(BRIEF)).append('\n')
        val range = plans.first().clauses.first..plans.last().clauses.last
        append("\nClauses (number [start–end seconds] text):\n").append(clauses(n, range))
        append("\nA suggested cut into chapters (improve it if the subject turns elsewhere):\n")
        plans.forEachIndexed { i, p -> append(i + 1).append(". c").append(p.clauses.first).append("–c").append(p.clauses.last).append(" «").append(p.title).append("»\n") }
        append("\nWrite the film of clauses c").append(range.first).append("–c").append(range.last)
            .append(": every chapter with its title, first and last clause numbers (the first chapter starts at c").append(range.first)
            .append(", the last ends at c").append(range.last).append(", no gaps), and its beats.")
    }

    /** One chapter, for a small model writing chapter by chapter. */
    fun scene(n: Narration, plans: List<Chapters.Plan>, i: Int): String = buildString {
        append("Narration language: ").append(languageName(n)).append('\n')
        append("Topic: ").append(n.understanding.topic.ifBlank { n.understanding.title }).append('\n')
        if (i > 0) append("Earlier chapters: ").append(plans.take(i).joinToString(", ") { "«${it.title}»" }).append('\n')
        if (i + 1 < plans.size) append("Later chapters: ").append(plans.drop(i + 1).joinToString(", ") { "«${it.title}»" }).append('\n')
        append("\nChapter ").append(i + 1).append(" of ").append(plans.size).append(", suggested title «").append(plans[i].title).append("». Its clauses:\n")
        append(clauses(n, plans[i].clauses))
        append("\nWrite this chapter's title and beats (clause numbers from the list above).")
    }

    fun outline(n: Narration): String = buildString {
        append("Narration language: ").append(languageName(n)).append("\n\nClauses:\n")
        n.clauses.forEach { c -> append('c').append(c.index).append(": ").append(c.text).append('\n') }
    }

    private fun fmt(v: Double) = (kotlin.math.round(v * 10) / 10).toString()

    private const val HINT = 0.95f
    private const val BRIEF = 600
    private const val GRAMMAR_CHARS = 1500
}
