package com.gte619n.healthfitness.feature.workouts.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the gym list screen. Fetches active locations on init and
 * exposes a single [UiState] flow per the IMPL-AND-00 convention.
 */
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

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repo.list().fold(
                onSuccess = { list ->
                    _state.update {
                        it.copy(
                            loading = false,
                            locations = list.sortedWith(
                                compareByDescending<Location> { l -> l.isDefault }
                                    .thenBy { l -> l.name.lowercase() },
                            ),
                            error = null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.localizedMessage ?: "Failed to load gyms",
                        )
                    }
                },
            )
        }
    }
}
