package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.forPortion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class NutritionTodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val day: NutritionDay? = null,
    val error: String? = null,
    /** entryIds with an in-flight delete, so the row can disable. */
    val pendingEntryIds: Set<String> = emptySet(),
    /** true while the add-food sheet is open. */
    val addSheetOpen: Boolean = false,
)

@HiltViewModel
class NutritionTodayViewModel @Inject constructor(
    private val repository: NutritionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionTodayUiState())
    val state: StateFlow<NutritionTodayUiState> = _state.asStateFlow()

    init {
        load(LocalDate.now())
    }

    fun previousDay() = load(_state.value.date.minusDays(1))

    fun nextDay() = load(_state.value.date.plusDays(1))

    fun refresh() = load(_state.value.date)

    fun openAddSheet() = _state.update { it.copy(addSheetOpen = true) }

    fun closeAddSheet() = _state.update { it.copy(addSheetOpen = false) }

    private fun load(date: LocalDate) {
        _state.update { it.copy(loading = true, date = date, error = null) }
        viewModelScope.launch {
            try {
                val day = repository.day(date.format(ISO_DATE))
                _state.update { it.copy(loading = false, day = day, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load nutrition")
                }
            }
        }
    }

    fun deleteEntry(entryId: String) {
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(pendingEntryIds = it.pendingEntryIds + entryId) }
        viewModelScope.launch {
            try {
                repository.deleteEntry(date, entryId)
                val day = repository.day(date)
                _state.update {
                    it.copy(day = day, pendingEntryIds = it.pendingEntryIds - entryId, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        pendingEntryIds = it.pendingEntryIds - entryId,
                        error = e.message ?: "Delete failed",
                    )
                }
            }
        }
    }

    /** Log a catalog food (chosen serving + quantity); macros snapshotted here. */
    fun addCatalogEntry(meal: Meal, food: Food, servingIndex: Int, quantity: Double) {
        val serving = food.servingSizes.getOrNull(servingIndex) ?: return
        val macros = food.macrosPer100g.forPortion(serving.grams, quantity)
        val body = EntryRequest(
            meal = meal.wire,
            foodId = food.foodId,
            foodName = food.name,
            servingLabel = serving.label,
            servingGrams = serving.grams,
            quantity = quantity,
            macros = macros,
            source = "CATALOG",
        )
        submit(body)
    }

    /** Quick ad-hoc entry: raw macros, no catalog food. */
    fun addQuickEntry(meal: Meal, name: String, macros: Macros) {
        val body = EntryRequest(
            meal = meal.wire,
            foodId = null,
            foodName = name,
            servingLabel = "1 serving",
            servingGrams = 100.0,
            quantity = 1.0,
            macros = macros,
            source = "MANUAL",
        )
        submit(body)
    }

    private fun submit(body: EntryRequest) {
        val date = _state.value.date.format(ISO_DATE)
        viewModelScope.launch {
            try {
                repository.addEntry(date, body)
                val day = repository.day(date)
                _state.update { it.copy(day = day, addSheetOpen = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }
}
