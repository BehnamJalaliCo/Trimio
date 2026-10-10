package io.trimio.engine.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.trimio.engine.llm.local.LocalModels
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore
import kotlinx.io.files.Path
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which installed director runs on which phone (files are sparse: only their size matters). */
class LocalModelsTest {
    private val dir = Files.createTempDirectory("local-models")
    private val store = ModelStore(Path(dir.toString()), HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))

    private fun install(vararg specs: ModelSpec) = specs.forEach { RandomAccessFile(dir.resolve(it.fileName).toFile(), "rw").use { f -> f.setLength(it.sizeBytes) } }

    @Test
    fun picksTheLargestComfortableModelAndHonoursAnyChoiceTheDeviceCanHold() {
        install(ModelCatalog.qwen35_4b, ModelCatalog.qwen35_4bEyes, ModelCatalog.qwen35_9bLight, ModelCatalog.qwen35_9b, ModelCatalog.qwen36_35bA3b)
        val phone12 = LocalModels(store, 12)
        assertEquals(ModelCatalog.qwen35_9b.id, phone12.best()?.id, "the largest that fits a 12 GB phone")
        assertEquals(ModelCatalog.qwen35_4b.id, phone12.best(ModelCatalog.qwen35_4b.id)?.id)
        assertTrue(phone12.best(ModelCatalog.qwen35_4b.id)!!.canSee, "the default sees with its own projector")
        // Too big for the phone: the choice falls back instead of crashing the app.
        assertTrue(ModelCatalog.qwen36_35bA3b !in phone12.installed())
        assertEquals(ModelCatalog.qwen35_9b.id, phone12.best(ModelCatalog.qwen36_35bA3b.id)?.id)
        // A 24 GB phone may try the experimental MoE, but never gets it unasked.
        val phone24 = LocalModels(store, 24)
        assertEquals(ModelCatalog.qwen36_35bA3b.id, phone24.best(ModelCatalog.qwen36_35bA3b.id)?.id)
        assertEquals(ModelCatalog.qwen35_9b.id, phone24.best()?.id)
        // 8 GB: only the default can be held; it still runs although the budget is tight.
        assertEquals(listOf(ModelCatalog.qwen35_4b), LocalModels(store, 8).installed())
        assertEquals(ModelCatalog.qwen35_4b.id, LocalModels(store, 8).best()?.id)
    }

    @Test
    fun gemma4GetsItsOwnTurnLayout() {
        val text = ChatTemplates.format(ChatFormat.Gemma4, "sys", listOf(ChatMessage(ChatRole.User, "hi")))
        assertEquals("<|turn>system\nsys<turn|>\n<|turn>user\nhi<turn|>\n<|turn>model\n", text)
    }
}
