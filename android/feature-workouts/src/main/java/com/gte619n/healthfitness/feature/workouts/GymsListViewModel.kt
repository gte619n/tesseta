package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.data.workouts.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GymsListViewModel @Inject constructor(
    private val repo: LocationRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val locations: List<Location> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            // Offline-first (ADR-0018): seed instantly from the Room mirror so
            // re-entry shows the last-synced gyms with no spinner. Only stay on
            // Loading when nothing is cached yet (pre-first-sync). Then revalidate.
            val cached = runCatching { repo.cachedList() }.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                _state.update { it.copy(loading = false, locations = cached, error = null) }
            } else {
                _state.update { it.copy(loading = true, error = null) }
            }
            repo.list().fold(
                onSuccess = { locations ->
                    _state.update { it.copy(loading = false, locations = locations, error = null) }
                },
                onFailure = { e ->
                    // Keep any seeded gyms on screen; only surface an error when
                    // there's nothing to show.
                    _state.update {
                        if (it.locations.isNotEmpty()) it.copy(loading = false)
                        else it.copy(loading = false, error = e.message ?: "Failed to load gyms")
                    }
                },
            )
        }
    }
}
