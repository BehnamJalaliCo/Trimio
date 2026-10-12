package io.trimio.desktop

import io.trimio.feature.library.AnimationFilePicker
import io.trimio.feature.library.PickedAnimations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * «افزودن انیمیشن» on desktop: the creator chooses Lottie files (`.json`, `.lottie`) or whole
 * folders; folders are searched (sub-folders too), `.lottie` archives unpacked (each animation in
 * them), and the first `.csv` found supplies keywords (`file,tags[,tags_fa]`).
 */
class DesktopAnimationPicker : AnimationFilePicker {
    override suspend fun pick(): PickedAnimations? {
        val chosen = withContext(Dispatchers.Swing) {
            val dialog = JFileChooser().apply {
                dialogTitle = "Add animations (.json, .lottie or a folder)"
                fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
                isMultiSelectionEnabled = true
                fileFilter = FileNameExtensionFilter("Lottie animations", "json", "lottie", "csv")
            }
            if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) dialog.selectedFiles.toList() else null
        } ?: return null
        return withContext(Dispatchers.IO) { read(chosen) }
    }

    companion object {
        private const val MAX_FILES = 2_000
        private const val MAX_BYTES = 20L * 1024 * 1024

        /** The animations under [chosen] (files and folders), and the first keywords CSV. */
        fun read(chosen: List<File>): PickedAnimations {
            val files = chosen.flatMap { f -> if (f.isDirectory) f.walkTopDown().filter { it.isFile }.toList() else listOf(f) }
                .filter { it.length() in 1..MAX_BYTES }.take(MAX_FILES)
            val out = mutableListOf<Pair<String, String>>()
            for (f in files) {
                when (f.extension.lowercase()) {
                    "json" -> runCatching { f.readText() }.getOrNull()?.let { out += f.name to it }
                    "lottie" -> out += dotLottie(f)
                }
            }
            val csv = files.firstOrNull { it.extension.equals("csv", true) }?.let { runCatching { it.readText() }.getOrNull() }
            return PickedAnimations(out, csv)
        }

        /** The animations of a dotLottie archive (`animations/<id>.json`), named after the archive. */
        private fun dotLottie(f: File): List<Pair<String, String>> = runCatching {
            ZipFile(f).use { zip ->
                val anims = zip.entries().toList().filter { !it.isDirectory && it.name.startsWith("animations/") && it.name.endsWith(".json") && it.size <= MAX_BYTES }
                anims.map { e ->
                    val name = if (anims.size == 1) f.nameWithoutExtension + ".json" else f.nameWithoutExtension + "-" + e.name.substringAfterLast('/')
                    name to zip.getInputStream(e).use { it.readBytes().decodeToString() }
                }
            }
        }.getOrDefault(emptyList())
    }
}
