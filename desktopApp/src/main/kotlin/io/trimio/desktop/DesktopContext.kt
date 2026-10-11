package io.trimio.desktop

import io.trimio.shared.IncomingMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the desktop rail shows and does; built once in `main` (and by the screenshot test). */
class DesktopContext(
    val dirs: DesktopDirs,
    val hardware: DesktopHardware,
    val picker: DesktopFilePicker,
    val sharer: DesktopSharer,
    val incoming: IncomingMedia,
    /** Asks the OS for GPUs off the UI thread; tests pass a fixed answer. */
    val detectGpus: suspend () -> List<Gpu> = { withContext(Dispatchers.IO) { DesktopHardware.detect().gpus } },
)
