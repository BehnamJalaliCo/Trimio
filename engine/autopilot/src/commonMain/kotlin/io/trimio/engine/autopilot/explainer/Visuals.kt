package io.trimio.engine.autopilot.explainer

/**
 * Who picks a picture query (English keywords for the art library) for what a sentence says. The
 * default reads the bundled lexicons ([Said.visualOf]); a retrieval index (embeddings, a Persian
 * glossary) can plug in here and every director's icons follow it.
 */
interface Visuals {
    /** The picture of [word] as said in [sentence], or null when it is not a thing that has one. */
    fun of(word: String, sentence: String): String? = Said.visualOf(word)

    /** A picture for the sentence as a whole when no word of it has one (null: none). */
    fun forSentence(sentence: String, persian: Boolean): String? = null

    /** The bundled lexicons only. */
    object Lexicon : Visuals
}
