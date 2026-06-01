package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.FoodRepository
import com.gte619n.healthfitness.domain.nutrition.Food
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddFoodUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Food> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class AddFoodViewModel @Inject constructor(
    private val foods: FoodRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddFoodUiState())
    val state: StateFlow<AddFoodUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searching = false, results = emptyList(), error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce keystrokes
            _state.update { it.copy(searching = true, error = null) }
            try {
                val results = foods.search(query)
                _state.update { it.copy(searching = false, results = results, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(searching = false, error = e.message ?: "Search failed")
                }
            }
        }
    }

    fun reset() {
        searchJob?.cancel()
        _state.value = AddFoodUiState()
    }
}
