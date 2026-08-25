package com.gte619n.healthfitness.feature.settings.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.settings.WorkoutSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the "Workout preferences" settings section: free-text standing
 * instructions (exercises to avoid, injuries to work around, style preferences)
 * that the program designer honors on every build. Source of truth is the backend
 * (synced across devices); this reads the [WorkoutSettingsRepository] cache flow
 * and writes changes through.
 */
@HiltViewModel
class WorkoutPreferencesViewModel @Inject constructor(
    private val repository: WorkoutSettingsRepository,
) : ViewModel() {

    /** Persistence state for the Save button. */
    enum class SaveState { IDLE, SAVING, SAVED, ERROR }

    /** The stored preferences, used to seed the editor. Empty string when unset. */
    val stored: StateFlow<String> =
        repository.preferences.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            "",
        )

    private val _saveState = MutableStateFlow(SaveState.IDLE)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /** Max characters accepted, mirroring the backend cap. */
    val maxLength: Int = MAX_LENGTH

    init {
        // Pull the authoritative value from the backend into the cache on open.
        viewModelScope.launch { repository.refresh() }
    }

    /** Persist the edited preferences (trimmed + capped). */
    fun save(text: String) {
        if (_saveState.value == SaveState.SAVING) return
        val capped = text.take(MAX_LENGTH)
        _saveState.value = SaveState.SAVING
        viewModelScope.launch {
            _saveState.value = runCatching { repository.setPreferences(capped) }
                .fold(onSuccess = { SaveState.SAVED }, onFailure = { SaveState.ERROR })
        }
    }

    /** Drop a transient SAVED/ERROR back to IDLE once the user edits again. */
    fun onEdited() {
        if (_saveState.value == SaveState.SAVED || _saveState.value == SaveState.ERROR) {
            _saveState.value = SaveState.IDLE
        }
    }

    private companion object {
        const val MAX_LENGTH = 2000
    }
}
