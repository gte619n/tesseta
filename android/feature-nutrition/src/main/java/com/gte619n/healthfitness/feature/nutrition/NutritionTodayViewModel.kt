package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.forPortion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /** the entry being edited, or null when the edit sheet is closed. */
    val editingEntry: Entry? = null,
    /** true while an entry edit is being saved. */
    val savingEdit: Boolean = false,
)

@HiltViewModel
class NutritionTodayViewModel @Inject constructor(
    private val repository: NutritionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionTodayUiState())
    val state: StateFlow<NutritionTodayUiState> = _state.asStateFlow()

    // Polls the day while any entry's image is still generating, so freshly
    // logged foods swap their placeholder for the studio image without the user
    // having to leave and return. Cancelled/replaced on each load.
    private var imagePollJob: Job? = null

    // First load (and every return to the foreground) is driven by the screen's
    // LifecycleResumeEffect, so there's no init load — that keeps the page from
    // double-fetching on open and lets it refresh after a capture pops back.

    fun previousDay() = load(_state.value.date.minusDays(1))

    fun nextDay() = load(_state.value.date.plusDays(1))

    fun refresh() = load(_state.value.date)

    fun openAddSheet() = _state.update { it.copy(addSheetOpen = true) }

    fun closeAddSheet() = _state.update { it.copy(addSheetOpen = false) }

    fun openEditSheet(entry: Entry) = _state.update { it.copy(editingEntry = entry) }

    fun closeEditSheet() = _state.update { it.copy(editingEntry = null) }

    private fun load(date: LocalDate) {
        // Stale-while-revalidate: only show the full-screen spinner when we have
        // nothing to show for this date yet. A same-date reload (e.g. resuming
        // after logging a barcode scan) keeps the current day on screen and swaps
        // in the fresh totals when they arrive — no flicker.
        val quiet = _state.value.day != null && _state.value.date == date
        _state.update { it.copy(loading = !quiet, date = date, error = null) }
        viewModelScope.launch {
            try {
                val day = repository.day(date.format(ISO_DATE))
                _state.update { it.copy(loading = false, day = day, error = null) }
                pollWhileImagesGenerate(date)
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, error = e.message ?: "Failed to load nutrition")
                }
            }
        }
    }

    /** True when at least one entry on the day still has a generating image. */
    private fun NutritionDay?.hasGeneratingImage(): Boolean =
        this?.meals?.any { group -> group.entries.any { it.imageStatus == "PENDING" } } == true

    /**
     * While any entry image is PENDING, re-fetch the day on a short interval and
     * swap in fresh data, so generated images appear as soon as they're ready.
     * Stops when nothing is pending (or after a cap, to avoid an endless loop on
     * a stuck generation), and only polls the still-current date.
     */
    private fun pollWhileImagesGenerate(date: LocalDate) {
        imagePollJob?.cancel()
        if (!_state.value.day.hasGeneratingImage()) return
        imagePollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < 20 && _state.value.date == date && _state.value.day.hasGeneratingImage()) {
                attempts++
                delay(2500)
                if (_state.value.date != date) return@launch
                val day = runCatching { repository.day(date.format(ISO_DATE)) }.getOrNull() ?: continue
                if (_state.value.date == date) {
                    _state.update { it.copy(day = day) }
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

    /** Edit an existing entry (serving / quantity / macros / meal), then reload. */
    fun updateEntry(entryId: String, patch: EntryPatchRequest) {
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(savingEdit = true) }
        viewModelScope.launch {
            try {
                repository.patchEntry(date, entryId, patch)
                val day = repository.day(date)
                _state.update {
                    it.copy(day = day, savingEdit = false, editingEntry = null, error = null)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(savingEdit = false, error = e.message ?: "Update failed")
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
