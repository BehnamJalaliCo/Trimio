package io.trimio.feature.create

import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.DirectorOffer
import io.trimio.engine.models.GraphicsProbe
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelState
import io.trimio.engine.models.SpeedModes
import io.trimio.engine.models.SpeedPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * The create screen's speeds as the device changes: a model installed or removed, and the
 * graphics card once it is known (the desktop asks the system, which takes a moment).
 */
class SpeedSource(
    private val models: ModelManager,
    private val device: DeviceMemory,
    private val offer: DirectorOffer = DirectorOffer.All,
    private val graphics: GraphicsProbe? = null,
) {
    /** [saved] is the director picked on the models screen, the default when the device holds it comfortably. */
    fun plans(saved: String?): Flow<SpeedPlan> {
        val card = flow {
            emit(null)
            graphics?.let { probe -> runCatching { probe.memory() }.getOrNull()?.let { emit(it) } }
        }
        return combine(models.states, card) { states, gpu ->
            SpeedModes.plan(offer.directors(models.catalog), { spec -> states[spec.id].let { it == ModelState.Installed || it is ModelState.UpdateAvailable } }, device, gpu, saved)
        }.distinctUntilChanged()
    }
}
