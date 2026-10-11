package io.trimio.feature.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.Clock
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home's state: the projects as the app builds them (stored projects joined with the live run and
 * their posters) and whether the director can work now.
 */
class StudioViewModel(
    projects: Flow<List<HomeProject>>,
    readiness: Flow<HomeReadiness>,
    private val picker: MediaPicker,
    initialReadiness: HomeReadiness = HomeReadiness(),
) : ViewModel() {

    val projects: StateFlow<List<HomeProject>> = projects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val readiness: StateFlow<HomeReadiness> = readiness.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), initialReadiness)

    fun now(): Long = Clock.now()

    /** Opens the system picker; [onPicked] runs only when the user chose something. */
    fun pick(kind: MediaKind, onPicked: (String) -> Unit) {
        viewModelScope.launch { picker.pick(kind)?.let { onPicked(it.value) } }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
