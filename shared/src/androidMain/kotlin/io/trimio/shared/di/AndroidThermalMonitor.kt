package io.trimio.shared.di

import android.content.Context
import android.os.PowerManager
import io.trimio.core.data.ThermalLevel
import io.trimio.core.data.ThermalMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** PowerManager's thermal status (Android 10+), mapped to the app's four levels. */
class AndroidThermalMonitor(context: Context) : ThermalMonitor {
    private val power = context.getSystemService(PowerManager::class.java)
    private val _level = MutableStateFlow(map(power.currentThermalStatus))
    override val level: StateFlow<ThermalLevel> = _level

    init {
        power.addThermalStatusListener(context.mainExecutor) { _level.value = map(it) }
    }

    private fun map(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.Normal
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.Warm
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.Hot
        else -> ThermalLevel.Critical
    }
}
