package io.trimio.engine.autopilot.workshop

import io.trimio.core.model.text.Numerals
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Caption words against the workshop's ground truth, through the spelling the autopilot applies
 * before anything is drawn (the brief, the word list, joins and splits) and the caption cleaning
 * (fillers out, sentence punctuation off). Prints, per scenario, the word errors (substitutions,
 * insertions, deletions after a word alignment) and each one, to build/workshop/language.txt.
 *
 * Compared as a reader sees them: punctuation, diacritics and the written ezafe («همهٔ») aside,
 * a number in digits or in words alike; half-spaces and letters count.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*LanguageBenchTest*' [-Pworkshop.only=fa-mixed,fa-tech]
 */
class LanguageBenchTest {
    @Test
    fun captionsAgainstTruth() = runBlocking {
        val only = System.getProperty("workshop.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val out = StringBuilder()
        var errors = 0
        var words = 0
        for (s in Workshop.scenarios().filter { s -> only == null || only.any { s.id.startsWith(it) } }) {
            val shown = numbers(captions(s))
            val truth = numbers(s.truth.map { it.text }.flatMap(::tokens))
            val diff = diff(truth, shown)
            errors += diff.size
            words += truth.size
            out.appendLine("${s.id}: ${diff.size}/${truth.size}")
            diff.forEach { out.appendLine("    $it") }
            if (System.getenv("WORKSHOP_HEARD") == "true") out.appendLine("  heard: ${s.words.joinToString(" ") { it.text }}")
        }
        out.appendLine("total: $errors/$words")
        File(Workshop.out, "language.txt").apply { parentFile.mkdirs() }.writeText(out.toString())
        println(out)
    }

    /** The caption words the viewer reads, in order. */
    private suspend fun captions(s: Scenario): List<String> {
        val heard = s.words.map { it.text }
        val brief = Proofreader.fromBrief(heard, s.prompt, PersianSpelling.load())
        val lexical = Proofreader.fromLexicon(heard, s.prompt, brief)
        val spelled = Transcript(s.spoken, s.words).withSpelling(brief + lexical)
        return spelled.words.filterNot { Words.isFiller(it.text) }.map { it.text }.flatMap(::tokens)
    }

    /** Each number as its value («نود و دو» and «۹۲» alike), so a number shown in digits is no error. */
    private fun numbers(tokens: List<String>): List<String> {
        val found = NumberWords.findAll(tokens).associateBy { it.start }
        val out = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val f = found[i]
            if (f == null) { out += tokens[i]; i++ } else { out += "#" + f.value.toLong() + if (f.percent) "%" else ""; i += f.count }
        }
        return out
    }

    private fun tokens(text: String): List<String> = text.split(' ').map(::plain).filter { it.isNotEmpty() }

    private fun plain(w: String): String {
        val s = Numerals.toLatin(w).replace('ي', 'ی').replace('ك', 'ک').replace('ۀ', 'ه')
            .filter { it.isLetterOrDigit() || it == '‌' || it == '%' }
        return s.trim('‌')
    }

    /** Word alignment of [shown] to [truth]: each substitution, insertion and deletion. */
    private fun diff(truth: List<String>, shown: List<String>): List<String> {
        val n = truth.size
        val m = shown.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (truth[i - 1] == shown[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val ops = mutableListOf<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (if (truth[i - 1] == shown[j - 1]) 0 else 1) -> {
                    if (truth[i - 1] != shown[j - 1]) ops += "«${truth[i - 1]}» shown «${shown[j - 1]}»"
                    i--; j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { ops += "«${truth[i - 1]}» missing"; i-- }
                else -> { ops += "extra «${shown[j - 1]}»"; j-- }
            }
        }
        return ops.reversed()
    }
}
