package com.gte619n.healthfitness.feature.workouts

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
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repo.list().fold(
                onSuccess = { locations ->
                    _state.update { it.copy(loading = false, locations = locations, error = null) }
                },
                onFailure = { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "Failed to load gyms") }
                },
            )
        }
    }
}
