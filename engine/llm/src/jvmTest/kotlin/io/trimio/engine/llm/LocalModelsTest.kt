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
    fun anInstalledDirectorAlwaysDirectsEvenAnExperimentalOne() {
        // The owner's laptop: only the 35B-A3B installed (its projector not), 32 GB. It used to fall to the rules.
        install(ModelCatalog.qwen36_35bA3b)
        val laptop = LocalModels(store, 32, device = io.trimio.engine.models.DeviceMemory.desktop(32))
        assertEquals(ModelCatalog.qwen36_35bA3b.id, laptop.best()?.id)
        assertEquals(null, laptop.whyNone())
        // Nothing that fits: the report says why the rules direct.
        val small = LocalModels(store, 8, device = io.trimio.engine.models.DeviceMemory.desktop(8))
        assertEquals(null, small.best())
        assertTrue(small.whyNone()!!.contains("too big"), small.whyNone())
    }

    @Test
    fun aModelFromTheCreatorsOwnDiskDirects() {
        // A stronger laptop: a 32B the creator already has, outside the models folder (sparse: only its size matters).
        val elsewhere = Files.createTempDirectory("own-disk")
        val file = elsewhere.resolve("Qwen3-32B-Q4_K_M.gguf").toFile()
        RandomAccessFile(file, "rw").use { f -> f.write("GGUF".toByteArray()); f.setLength(19_800_000_000L) }
        val own = store.addOwn(file.path).getOrThrow()
        assertEquals(file.path, store.pathOf(own).toString(), "used where it lies, never copied")
        assertTrue(store.isInstalled(own))
        assertEquals(io.trimio.engine.models.DirectorProfile.Qwen35, io.trimio.engine.models.DirectorProfile.of(own), "Qwen3 answers past its reasoning block")
        val laptop = LocalModels(store, 64, ModelCatalog.all + store.ownModels(), io.trimio.engine.models.DeviceMemory.desktop(64))
        assertEquals(own.id, laptop.bestSpec(own.id)?.id, "the creator's pick directs")
        // The same file twice is one entry; projectors, other files and tiny files are refused with their reason.
        store.addOwn(file.path).getOrThrow()
        assertEquals(1, store.ownModels().size)
        fun refusal(name: String, bytes: ByteArray, size: Long): io.trimio.engine.models.OwnModels.Refusal? {
            val f = elsewhere.resolve(name).toFile()
            RandomAccessFile(f, "rw").use { it.write(bytes); it.setLength(size) }
            return (store.addOwn(f.path).exceptionOrNull() as? io.trimio.engine.models.OwnModels.RefusedException)?.reason
        }
        assertEquals(io.trimio.engine.models.OwnModels.Refusal.Projector, refusal("mmproj-Qwen3-F16.gguf", "GGUF".toByteArray(), 900_000_000L))
        assertEquals(io.trimio.engine.models.OwnModels.Refusal.NotGguf, refusal("model.safetensors", "{\"__".toByteArray(), 900_000_000L))
        assertEquals(io.trimio.engine.models.OwnModels.Refusal.TooSmall, refusal("tiny.gguf", "GGUF".toByteArray(), 1_000_000L))
        val gone = store.addOwn(elsewhere.resolve("gone.gguf").toString()).exceptionOrNull()
        assertEquals(io.trimio.engine.models.OwnModels.Refusal.Missing, (gone as? io.trimio.engine.models.OwnModels.RefusedException)?.reason)
        // Removing it forgets it; the file is the creator's and stays.
        store.delete(own)
        assertTrue(store.ownModels().isEmpty())
        assertTrue(file.isFile)
    }

    @Test
    fun gemma4GetsItsOwnTurnLayout() {
        val text = ChatTemplates.format(ChatFormat.Gemma4, "sys", listOf(ChatMessage(ChatRole.User, "hi")))
        assertEquals("<|turn>system\nsys<turn|>\n<|turn>user\nhi<turn|>\n<|turn>model\n", text)
    }
}
