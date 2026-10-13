package io.trimio.engine.retrieval

import io.trimio.engine.llm.local.LlamaEmbedder
import io.trimio.engine.llm.local.LocalEncoders
import io.trimio.engine.models.TextAddOns
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The offline vector-pack builder (tools/retrieval/build_packs.sh runs it): embeds every bundled
 * corpus — the picture catalogue as the app reads it ([Corpora.pictures] over the autopilot's art
 * index), the primitives, card families, sounds, golden exemplars and glossary — with the same
 * embedder and prompts the device uses ([TextAddOns.embedder]), and writes one int8 bundle per vector space to
 * `src/commonMain/composeResources/files/retrieval/packs/`. Runs only with `-Pretrieval.build=packs`.
 */
class VectorPackBuildTest {
    @Test
    fun buildPacks() = runBlocking {
        if (System.getProperty("retrieval.build") != "packs") return@runBlocking
        val dir = File(requireNotNull(System.getProperty("retrieval.encoders")) { "-Pretrieval.encoders=<models dir>" })
        val spec = TextAddOns.embedder
        val file = File(dir, spec.fileName)
        require(file.isFile) { "missing ${file.path}" }
        LlamaEmbedder(spec.id, file.absolutePath, LocalEncoders.familyOf(spec)).use { e ->
            e.load()
            val pictures = Corpora.pictures(File("../autopilot/src/commonMain/composeResources/files/art/index.json").readText())
            val bundled = Corpora.bundled()
            val t0 = System.nanoTime()
            val r = Retrieval.open(pictures, e, bundled = bundled) { done, total -> if (done % 256 == 0 || done == total) println("packs: $done/$total") }
            val packs = listOf(r.visualIndex, r.soundIndex, r.exemplarIndex, r.termIndex).map { requireNotNull(it.pack()) }
            val bytes = VectorPack.writeAll(packs)
            val out = File("src/commonMain/composeResources/files/retrieval/packs").also { it.mkdirs() }
            File(out, Corpora.packFile(e.space)).writeBytes(bytes)
            println("packs: ${e.space}: ${packs.sumOf { it.entries.size }} vectors, ${bytes.size / 1024} KB in ${(System.nanoTime() - t0) / 1_000_000_000} s")
            // The bundle reads back, and a fresh index built from it embeds nothing.
            val back = VectorPack.readAll(bytes)
            assertEquals(packs.map { it.corpus }, back.map { it.corpus })
            val again = CorpusIndex(Corpora.SOUNDS, bundled.sounds)
            assertEquals(0, again.attach(e, back))
            assertTrue(bytes.size < MAX_BYTES)
        }
    }

    private companion object {
        /** Packs ship in the app only while they stay small. */
        const val MAX_BYTES = 20 shl 20
    }
}
