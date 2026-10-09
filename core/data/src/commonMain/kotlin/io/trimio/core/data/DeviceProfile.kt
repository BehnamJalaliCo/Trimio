package io.trimio.core.data

import io.trimio.core.model.input.Resolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How hot the phone is, from the OS thermal API. */
enum class ThermalLevel { Normal, Warm, Hot, Critical }

/** Source of [ThermalLevel]; Android reads PowerManager, other platforms report Normal. */
interface ThermalMonitor {
    val level: StateFlow<ThermalLevel>

    companion object {
        val None: ThermalMonitor = object : ThermalMonitor {
            override val level: StateFlow<ThermalLevel> = MutableStateFlow(ThermalLevel.Normal)
        }
    }
}

/**
 * What this phone should run by default, so a first-time user gets the best result their hardware
 * can sustain without tuning anything.
 */
data class DeviceProfile(
    val tier: Tier,
    /** Worker threads for speech and language models (big cores only). */
    val threads: Int,
    /** Highest export resolution offered as the default. */
    val defaultResolution: Resolution,
    /** Full-screen shader backgrounds in live previews render at this fraction of the screen. */
    val previewShaderScale: Float,
) {
    enum class Tier { Standard, High, Ultra }

    /** Threads adjusted for heat: a throttling phone runs slower anyway, and fewer threads keep it from getting worse. */
    fun threadsFor(level: ThermalLevel): Int = when (level) {
        ThermalLevel.Normal -> threads
        ThermalLevel.Warm -> (threads - 1).coerceAtLeast(2)
        ThermalLevel.Hot -> (threads / 2).coerceAtLeast(2)
        ThermalLevel.Critical -> 1
    }

    companion object {
        fun of(ramGb: Int, cores: Int): DeviceProfile {
            val tier = when {
                ramGb >= 16 -> Tier.Ultra
                ramGb >= 12 -> Tier.High
                else -> Tier.Standard
            }
            // Flagship SoCs pair 2–4 performance cores with efficiency cores; half the cores ≈ the big ones.
            val threads = (cores / 2).coerceIn(2, 6)
            return DeviceProfile(
                tier = tier,
                threads = threads,
                defaultResolution = if (tier == Tier.Ultra) Resolution.Uhd4k else Resolution.FullHd,
                previewShaderScale = when (tier) {
                    Tier.Ultra -> 1f
                    Tier.High -> 0.75f
                    Tier.Standard -> 0.5f
                },
            )
        }
    }
}
