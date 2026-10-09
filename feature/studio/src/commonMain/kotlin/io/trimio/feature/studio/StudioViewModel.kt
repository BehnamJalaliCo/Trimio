package io.trimio.feature.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.Clock
import io.trimio.core.data.MediaKind
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class StudioViewModel(
    private val repository: ProjectRepository,
    private val picker: MediaPicker,
) : ViewModel() {

    val projects: StateFlow<List<Project>> = repository.projects

    init {
        viewModelScope.launch { repository.load() }
    }

    fun now(): Long = Clock.now()

    /** Opens the system picker; [onPicked] runs only when the user chose something. */
    fun pick(kind: MediaKind, onPicked: (String) -> Unit) {
        viewModelScope.launch { picker.pick(kind)?.let { onPicked(it.value) } }
    }
}
