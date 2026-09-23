package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.Food
import com.gte619n.healthfitness.shared.data.FoodRepository
import com.gte619n.healthfitness.shared.data.MealSearchResult
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * IMPL-IOS-01 Phase 3 Wave C — shared add-food / saved-meal-relog VM. Port of
 * Android `feature-nutrition/AddFoodViewModel`. Local-first search: serve cached
 * foods/meals instantly, then a debounced network pass revalidates. `recents`
 * (distinct foods/meals from the last two weeks, biased to this time of day) is
 * the empty-query default; a tap re-logs via the Today VM.
 *
 * The current-meal bias comes in as a param ([currentMeal]) since commonMain has
 * no java.time clock; the SwiftUI layer supplies `Meal.forHour(...)`.
 */
data class AddFoodUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<Food> = emptyList(),
    val mealResults: List<MealSearchResult> = emptyList(),
    val error: String? = null,
    val recents: List<Entry> = emptyList(),
    val recentsLoading: Boolean = true,
)

class AddFoodViewModel(
    private val foods: FoodRepository,
    private val nutrition: NutritionDayRepository,
    private val currentMeal: Meal,
) : ViewModel() {

    private val _state = MutableStateFlow(AddFoodUiState())
    val state: StateFlow<AddFoodUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init { loadRecents() }

    private fun loadRecents() {
        viewModelScope.launch {
            // local-first: render recents from the synced mirror (instant, offline)…
            val cached = runCatching { nutrition.cachedRecentMeals(meal = currentMeal.wire) }
                .getOrElse { emptyList() }
            if (cached.isNotEmpty()) {
                _state.update { it.copy(recents = cached, recentsLoading = false) }
                launch { runCatching { foods.warmFromIds(cached.mapNotNull { e -> e.foodId }) } }
            }
            // …then revalidate from the network (authoritative ordering/freshness).
            runCatching { nutrition.recentMeals(meal = currentMeal.wire) }
                .onSuccess { fresh -> _state.update { it.copy(recents = fresh, recentsLoading = false) } }
                .onFailure { _state.update { it.copy(recentsLoading = false) } }
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
            // LOCAL PASS — no debounce: serve cached foods/meals the instant the
            // user types, so the list never blocks on the network.
            val localFoods = runCatching { foods.localSearch(query) }.getOrElse { emptyList() }
            val localMeals = runCatching { nutrition.cachedSearchMeals(query) }.getOrElse { emptyList() }
            _state.update { st ->
                st.copy(
                    results = localFoods.ifEmpty { st.results },
                    mealResults = localMeals.ifEmpty { st.mealResults },
                    searching = true,
                    error = null,
                )
            }
            // NETWORK PASS — debounced revalidation. Success is authoritative;
            // failure leaves the local results on screen.
            delay(220)
            try {
                supervisorScope {
                    val mealsJob = launch {
                        runCatching { nutrition.searchMeals(query) }
                            .onSuccess { meals -> _state.update { it.copy(mealResults = meals) } }
                    }
                    val net = foods.search(query)
                    _state.update { it.copy(results = net, error = null) }
                    mealsJob.join()
                    _state.update { it.copy(searching = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        searching = false,
                        error = if (it.results.isEmpty() && it.mealResults.isEmpty()) {
                            e.message ?: "Search failed"
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    /** Archive a saved meal from the results (optimistic drop). */
    fun onArchiveMeal(mealId: String) {
        _state.update { it.copy(mealResults = it.mealResults.filterNot { m -> m.mealId == mealId }) }
        viewModelScope.launch { runCatching { nutrition.archiveMeal(mealId) } }
    }

    /** Delete a catalog food from the results (optimistic drop, restore on failure). */
    fun onDeleteFood(foodId: String) {
        val previous = _state.value.results
        _state.update { it.copy(results = it.results.filterNot { f -> f.foodId == foodId }) }
        viewModelScope.launch {
            runCatching { foods.deleteFood(foodId) }
                .onFailure { _state.update { st -> st.copy(results = previous) } }
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
