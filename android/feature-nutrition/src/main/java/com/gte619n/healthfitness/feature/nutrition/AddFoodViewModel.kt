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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
            // Bias the list toward meals usually eaten at this time of day
            // (breakfasts at breakfast time, etc.) — same window the sheet
            // uses to pre-select the meal chip.
            val currentMeal = Meal.forHour(LocalTime.now().hour)
            // local-first: render recents straight from the synced Room mirror so
            // the sheet opens instantly and works offline…
            val cached = runCatching { nutrition.cachedRecentMeals(meal = currentMeal.wire) }
                .getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                _state.update { it.copy(recents = cached, recentsLoading = false) }
                // …and seed the local food cache with the foods behind these
                // recents so name search finds them instantly/offline too
                // (best-effort, background; warmFromIds skips already-cached ids).
                launch { runCatching { foods.warmFromIds(cached.mapNotNull { e -> e.foodId }) } }
            }
            // …then revalidate from the network (authoritative ordering/freshness).
            runCatching { nutrition.recentMeals(meal = currentMeal.wire) }
                .onSuccess { fresh -> _state.update { it.copy(recents = fresh, recentsLoading = false) } }
                // Recents are a convenience; a failed refresh just leaves the
                // cached (or empty) list — search/describe/quick-add still work.
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
            // LOCAL PASS — no debounce: serve cached foods/meals the INSTANT the
            // user types, so the list never blocks on the network (or a cold
            // backend). This is the local-first win; the network pass below just
            // revalidates.
            val localFoods = runCatching { foods.localSearch(query) }.getOrDefault(emptyList())
            val localMeals = runCatching { nutrition.cachedSearchMeals(query) }.getOrDefault(emptyList())
            _state.update { st ->
                st.copy(
                    // Show local hits now; if we have none for THIS query yet, keep
                    // the prior results visible (with the spinner) to avoid flashing
                    // empty between keystrokes.
                    results = localFoods.ifEmpty { st.results },
                    mealResults = localMeals.ifEmpty { st.mealResults },
                    searching = true,
                    error = null,
                )
            }
            // NETWORK PASS — debounced revalidation. A SUCCESSFUL network read is
            // authoritative and replaces the local guess; a FAILURE leaves the
            // local results on screen instead of surfacing an error.
            delay(220) // debounce keystrokes before the network call
            try {
                // supervisorScope so a failing child surfaces ONLY through its own
                // failure path (the foods call below, caught here), instead of
                // propagating up the Job hierarchy to the uncaught-exception handler
                // and force-closing the app.
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
                // A newer keystroke cancelled this search; it isn't an error —
                // let the replacement job drive the UI. (Rethrow to preserve
                // structured concurrency rather than surface the cancellation.)
                throw e
            } catch (e: Exception) {
                // Network failed — local results may already be on screen. Only
                // surface an error when there's genuinely nothing to show.
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

    /**
     * Archive a saved meal from the search results. Drops it from the visible
     * list right away (optimistic) and tells the backend to hide it from future
     * searches; already-logged entries are unaffected. A network failure just
     * means it may reappear on the next search — no user-facing error.
     */
    fun onArchiveMeal(mealId: String) {
        _state.update { it.copy(mealResults = it.mealResults.filterNot { m -> m.mealId == mealId }) }
        viewModelScope.launch {
            runCatching { nutrition.archiveMeal(mealId) }
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
