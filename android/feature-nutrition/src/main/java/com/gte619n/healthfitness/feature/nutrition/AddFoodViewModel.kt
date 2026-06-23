package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.FoodRepository
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealSearchResult
import java.time.LocalTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    // Saved-meal hits, shown as a "Saved meals" group above the catalog foods.
    val mealResults: List<MealSearchResult> = emptyList(),
    val error: String? = null,
    // One-tap "recent meals": the user's distinct foods/meals from the last
    // two weeks, shown as the sheet's default (empty-query) content.
    val recents: List<Entry> = emptyList(),
    val recentsLoading: Boolean = true,
)

@HiltViewModel
class AddFoodViewModel @Inject constructor(
    private val foods: FoodRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AddFoodUiState())
    val state: StateFlow<AddFoodUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadRecents()
    }

    private fun loadRecents() {
        viewModelScope.launch {
            try {
                // Bias the list toward meals usually eaten at this time of day
                // (breakfasts at breakfast time, etc.) — same window the sheet
                // uses to pre-select the meal chip.
                val currentMeal = Meal.forHour(LocalTime.now().hour)
                val recents = nutrition.recentMeals(meal = currentMeal.wire)
                _state.update { it.copy(recents = recents, recentsLoading = false) }
            } catch (e: Exception) {
                // Recents are a convenience; a failed load just leaves the list
                // empty — search/describe/quick-add still work.
                _state.update { it.copy(recentsLoading = false) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(searching = false, results = emptyList(), mealResults = emptyList(), error = null)
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(220) // debounce keystrokes
            _state.update { it.copy(searching = true, error = null) }
            // Saved meals and catalog foods are searched in parallel; a failure in
            // the meal search just leaves that group empty (catalog still works).
            try {
                val mealsDeferred = async {
                    runCatching { nutrition.searchMeals(query) }.getOrDefault(emptyList())
                }
                val foodsDeferred = async { foods.search(query) }
                val meals = mealsDeferred.await()
                val results = foodsDeferred.await()
                _state.update {
                    it.copy(
                        searching = false,
                        results = results,
                        mealResults = meals,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(searching = false, error = e.message ?: "Search failed")
                }
            }
        }
    }

    fun reset() {
        searchJob?.cancel()
        _state.value = AddFoodUiState(
            recents = _state.value.recents,
            recentsLoading = _state.value.recentsLoading,
        )
    }
}
