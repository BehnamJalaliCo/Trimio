package io.trimio.desktop

import io.trimio.feature.settings.ModelFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** «افزودن مدل از روی سیستم» on Windows: one `.gguf` language model chosen on this computer (its path; nothing is copied). */
class DesktopModelPicker : ModelFilePicker {
    override suspend fun pick(): String? = withContext(Dispatchers.Swing) {
        val dialog = JFileChooser().apply {
            dialogTitle = "Add a director model (.gguf)"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isMultiSelectionEnabled = false
            fileFilter = FileNameExtensionFilter("GGUF language models", "gguf")
        }
        if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) dialog.selectedFile?.absolutePath else null
    }

    companion object {
        /** The owner's call: only the Windows app adds models from the disk. */
        val offered: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Windows")
    }
}
