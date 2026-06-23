package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.nutrition.PendingCaptureStore
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.MealSearchResult
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.nutrition.UpdateIngredientRequest
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

// Photo-meal analysis (itemize + generate the finished-meal/ingredient images)
// can take a few minutes. FCM normally wakes the app the instant the server
// finalizes the entry, but the foreground screen still needs to keep checking
// in case the push is missed — so the settle-poll runs generously (120 × 2.5s ≈
// 5 min) rather than the old ~50 s, which gave up long before image-bearing
// meals finished and left the row stuck on "Analyzing photo…".
private const val MAX_SETTLE_POLL_ATTEMPTS = 120

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
    /** the composite meal whose ingredients sheet is open, or null. */
    val editingComposite: Entry? = null,
    /** true while an ingredient portion is being saved. */
    val savingIngredient: Boolean = false,
    /** true while a user-initiated pull-to-refresh is in flight. */
    val isRefreshing: Boolean = false,
    /**
     * Capture photos still uploading in the background (B2 instant capture).
     * The screen renders one synthetic "Uploading photo…" row per item, in the
     * meal group the capture targeted, until the server's ANALYZING placeholder
     * replaces it.
     */
    val pendingCaptures: List<PendingCaptureStore.PendingCapture> = emptyList(),
)

@HiltViewModel
class NutritionTodayViewModel @Inject constructor(
    private val repository: NutritionRepository,
    pendingCaptures: PendingCaptureStore,
    syncSignals: SyncSignals,
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

    init {
        // A photo meal finalizes on the backend (ANALYZING → READY) well after
        // capture; the server pushes an FCM sync wakeup when it does. Nutrition
        // reads over REST (not the mirror), so re-fetch the day on any push whose
        // hint names nutrition — this lets stragglers that finish after the
        // settle-poll's budget elapses still appear without a manual nudge. The
        // collect runs only while the screen is alive; same-date reloads are
        // quiet, so the refresh is invisible unless something actually changed.
        viewModelScope.launch {
            syncSignals.pushes.collect { collections ->
                if (collections == null || collections.contains("nutrition", ignoreCase = true)) {
                    refresh()
                }
            }
        }
        // Mirror the in-flight capture uploads into state. When an upload
        // completes (the list shrinks) the worker has already pulled the
        // server's ANALYZING placeholder into the mirror — re-load so the
        // synthetic row swaps for the real one and the settle-poll engages.
        viewModelScope.launch {
            var previous = emptyList<PendingCaptureStore.PendingCapture>()
            pendingCaptures.pending.collect { captures ->
                val completed = previous.size > captures.size
                previous = captures
                _state.update { it.copy(pendingCaptures = captures) }
                if (completed) refresh()
            }
        }
    }

    fun previousDay() = load(_state.value.date.minusDays(1))

    fun nextDay() = load(_state.value.date.plusDays(1))

    fun refresh() = load(_state.value.date)

    /** Swipe-down pull-to-refresh: re-fetch the current day, showing the
     *  refresh indicator until it settles. */
    fun onPullRefresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            // Pull-to-refresh is an explicit "get me the latest". Force a network
            // re-pull (refreshDay) rather than the mirror-gated day(): a logged
            // meal whose generated image only finalizes server-side AFTER the
            // settle-poll budget elapses leaves a non-PENDING mirror row that
            // day() never re-fetches — so its image would never appear no matter
            // how many times the user pulls to refresh. refreshDay reconciles it.
            runCatching { repository.refreshDay(_state.value.date.format(ISO_DATE)) }
            load(_state.value.date)
        }
    }

    fun openAddSheet() = _state.update { it.copy(addSheetOpen = true) }

    fun closeAddSheet() = _state.update { it.copy(addSheetOpen = false) }

    // A composite (photo-logged) meal opens the ingredients sheet; everything
    // else opens the single-food edit sheet. Synthetic uploading rows have no
    // server entry yet — nothing to edit.
    fun openEditSheet(entry: Entry) {
        if (entry.entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update {
            if (entry.isComposite) it.copy(editingComposite = entry) else it.copy(editingEntry = entry)
        }
    }

    fun closeEditSheet() = _state.update { it.copy(editingEntry = null, editingComposite = null) }

    /**
     * Save the whole composite meal in one go: rename it (if the title changed)
     * and re-portion each ingredient whose quantity multiplier changed, then
     * reload and close the sheet.
     */
    fun saveCompositeMeal(
        entryId: String,
        title: String,
        portion: Double,
        quantities: List<Double>,
    ) {
        val date = _state.value.date.format(ISO_DATE)
        val current = _state.value.editingComposite ?: return
        _state.update { it.copy(savingIngredient = true) }
        viewModelScope.launch {
            try {
                // Ingredient quantity changes first — each resum preserves the
                // existing portion — then patch the title/portion so the entry's
                // total reflects the fresh ingredient totals scaled by it.
                current.ingredients?.forEachIndexed { i, ing ->
                    val newQty = quantities.getOrNull(i) ?: (ing.quantity ?: 1.0)
                    if ((ing.quantity ?: 1.0) != newQty) {
                        repository.updateIngredient(
                            date, entryId, i, UpdateIngredientRequest(quantity = newQty),
                        )
                    }
                }
                val newTitle = title.takeIf { it.isNotBlank() && it != current.foodName }
                val newPortion = portion.takeIf { it != current.quantity }
                if (newTitle != null || newPortion != null) {
                    repository.patchEntry(
                        date, entryId,
                        EntryPatchRequest(foodName = newTitle, quantity = newPortion),
                    )
                }
                val day = repository.day(date)
                _state.update {
                    it.copy(day = day, savingIngredient = false, editingComposite = null, error = null)
                }
            } catch (e: Exception) {
                _state.update { it.copy(savingIngredient = false, error = e.message ?: "Save failed") }
            }
        }
    }

    private fun load(date: LocalDate) {
        // offline-fix — cache-first, revalidate in the background. If we're already
        // showing this date, stay quiet (stale-while-revalidate). Otherwise seed
        // INSTANTLY from the mirror-only cachedDay so the day snaps in with no
        // spinner; the full-screen loader now shows only when there's genuinely
        // nothing cached yet (i.e. before the first sync).
        val sameDateShown = _state.value.day != null && _state.value.date == date
        viewModelScope.launch {
            if (!sameDateShown) {
                val cached = runCatching { repository.cachedDay(date.format(ISO_DATE)) }.getOrNull()
                _state.update { it.copy(date = date, day = cached, loading = cached == null, error = null) }
            } else {
                _state.update { it.copy(date = date, error = null) }
            }
            try {
                val day = repository.day(date.format(ISO_DATE))
                _state.update { it.copy(loading = false, isRefreshing = false, day = day, error = null) }
                pollWhileImagesGenerate(date)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        isRefreshing = false,
                        // Keep any cached/seeded day on screen; only surface the
                        // error when we have nothing to show.
                        error = if (it.day == null) (e.message ?: "Failed to load nutrition") else null,
                    )
                }
            }
        }
    }

    /**
     * True when at least one entry on the day is still settling: its image is
     * generating (PENDING), its captured photo is still being analyzed
     * (ANALYZING), or it has an unsynced local mutation in flight
     * (`syncState == "PENDING"` — e.g. just moved between meals). Any of these
     * keeps the poll alive so the row — and its sync badge — updates in place
     * once the outbox drain flips it to SYNCED.
     */
    private fun NutritionDay?.hasGeneratingImage(): Boolean =
        this?.meals?.any { group ->
            group.entries.any {
                it.imageStatus == "PENDING" || it.isAnalyzing || it.syncState == "PENDING"
            }
        } == true

    /**
     * While any entry is still analyzing or generating its image, re-fetch the
     * day on a short interval and swap in fresh data, so a captured photo's name,
     * macros and image appear as soon as they're ready. Stops when nothing is
     * pending (or after a cap, to avoid an endless loop on a stuck generation),
     * and only polls the still-current date.
     */
    private fun pollWhileImagesGenerate(date: LocalDate) {
        imagePollJob?.cancel()
        if (!_state.value.day.hasGeneratingImage()) return
        imagePollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_SETTLE_POLL_ATTEMPTS && _state.value.date == date && _state.value.day.hasGeneratingImage()) {
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

    /**
     * IMPL-STAB (Workstream E): retry a failed food-image generation. Optimistically
     * flip the row to PENDING so it shows the spinner immediately, ask the backend
     * to regenerate, then let the settle-poll swap in the finished image.
     */
    fun regenerateEntryImage(entryId: String) {
        val date = _state.value.date.format(ISO_DATE)
        viewModelScope.launch {
            try {
                repository.regenerateEntryImage(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't retry the image") }
            }
        }
    }

    fun deleteEntry(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
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

    /**
     * Move an entry to another meal via drag-and-drop. Updates the day
     * optimistically (so the row hops sections immediately and both subtotals
     * re-sum), PATCHes the entry's meal, then reloads. A failure reverts.
     */
    fun moveEntry(entryId: String, targetMeal: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val current = _state.value.day ?: return
        val source = current.meals.firstOrNull { g -> g.entries.any { it.entryId == entryId } } ?: return
        if (source.meal == targetMeal) return
        val entry = source.entries.first { it.entryId == entryId }

        _state.update { it.copy(day = current.withEntryMoved(entry, targetMeal)) }
        val date = _state.value.date.format(ISO_DATE)
        viewModelScope.launch {
            try {
                repository.patchEntry(date, entryId, EntryPatchRequest(meal = targetMeal))
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                // Keep refreshing so the row's PENDING badge clears once the
                // optimistic move drains to the server.
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(day = current, error = e.message ?: "Move failed") }
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
                pollWhileImagesGenerate(_state.value.date)
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

    /**
     * Fire-and-forget describe: the sheet closes immediately, the server logs an
     * ANALYZING placeholder named with the description, and the settle-poll
     * fills it in (the camera-capture pattern). The only wait is the quick 202.
     */
    fun describeMealAsync(meal: Meal, description: String) {
        val text = description.trim()
        if (text.isBlank()) return
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(addSheetOpen = false) }
        viewModelScope.launch {
            try {
                repository.describeMealAsync(date, text, meal.wire)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Describe failed") }
            }
        }
    }

    /**
     * One-tap re-log of a recent entry (same portions) onto the current day's
     * [meal]. Server-side copy — catalog foods and images are reused, so the
     * row lands complete with no AI wait.
     */
    fun relogRecent(meal: Meal, entry: Entry) {
        val sourceDate = entry.date ?: return
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(addSheetOpen = false) }
        viewModelScope.launch {
            try {
                repository.relog(date, sourceDate, entry.entryId, meal.wire)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }

    /**
     * Log a saved meal (from the add sheet's "Saved meals" search group) by id
     * onto the current day's [meal]. Reuses the meal's ingredient breakdown and
     * plated photo — no AI rework — though the image may still be generating, so
     * we poll like the describe flow.
     */
    fun logSavedMeal(meal: Meal, result: MealSearchResult) {
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(addSheetOpen = false) }
        viewModelScope.launch {
            try {
                repository.logDescribedMeal(date, result.mealId, meal.wire)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }

    private fun submit(body: EntryRequest) {
        val date = _state.value.date.format(ISO_DATE)
        viewModelScope.launch {
            try {
                repository.addEntry(date, body)
                val day = repository.day(date)
                _state.update { it.copy(day = day, addSheetOpen = false, error = null) }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }
}

/** Id prefix of the synthetic rows shown while a capture photo uploads. */
const val PENDING_CAPTURE_PREFIX = "pending-capture-"

/**
 * Merge the in-flight capture uploads into the day for display: one synthetic
 * "Uploading photo…" row (ANALYZING, zero macros) per capture targeting [date],
 * appended to its target meal group. Pure presentation — totals are untouched
 * (an uploading photo contributes nothing yet, same as a server placeholder).
 */
fun NutritionDay?.withPendingCaptures(
    captures: List<PendingCaptureStore.PendingCapture>,
    date: LocalDate,
): NutritionDay? {
    val forDate = captures.filter { it.date == date.format(ISO_DATE) }
    if (forDate.isEmpty()) return this
    val base = this ?: NutritionDay(date = date.format(ISO_DATE), totals = Macros.EMPTY)
    var meals = base.meals
    forDate.forEach { capture ->
        val synthetic = Entry(
            entryId = PENDING_CAPTURE_PREFIX + capture.id,
            meal = capture.mealWire,
            foodName = "Uploading photo…",
            quantity = 1.0,
            macros = Macros.EMPTY,
            source = "PHOTO",
            analysisStatus = "ANALYZING",
        )
        meals = if (meals.any { it.meal == capture.mealWire }) {
            meals.map { g ->
                if (g.meal == capture.mealWire) g.copy(entries = g.entries + synthetic) else g
            }
        } else {
            meals + MealGroup(meal = capture.mealWire, subtotal = Macros.EMPTY, entries = listOf(synthetic))
        }
    }
    return base.copy(meals = meals)
}

/**
 * Return a copy of this day with [entry] moved into [targetMeal]: it leaves its
 * source group and joins the target group (created if the day had no entries
 * there yet), with both groups' subtotals re-summed.
 */
private fun NutritionDay.withEntryMoved(entry: Entry, targetMeal: String): NutritionDay {
    val moved = entry.copy(meal = targetMeal)
    val withoutEntry = meals.map { g ->
        if (g.entries.any { it.entryId == entry.entryId }) {
            val entries = g.entries.filterNot { it.entryId == entry.entryId }
            g.copy(entries = entries, subtotal = entries.sumMacros())
        } else {
            g
        }
    }
    val hasTarget = withoutEntry.any { it.meal == targetMeal }
    val withTarget = if (hasTarget) {
        withoutEntry.map { g ->
            if (g.meal == targetMeal) {
                val entries = g.entries + moved
                g.copy(entries = entries, subtotal = entries.sumMacros())
            } else {
                g
            }
        }
    } else {
        withoutEntry + MealGroup(meal = targetMeal, subtotal = listOf(moved).sumMacros(), entries = listOf(moved))
    }
    return copy(meals = withTarget)
}

/** Sum a list of entries' macros into a single subtotal snapshot. */
private fun List<Entry>.sumMacros(): Macros = Macros(
    caloriesKcal = sumOf { it.macros.caloriesKcal ?: 0.0 },
    proteinGrams = sumOf { it.macros.proteinGrams ?: 0.0 },
    carbsGrams = sumOf { it.macros.carbsGrams ?: 0.0 },
    fatGrams = sumOf { it.macros.fatGrams ?: 0.0 },
    fiberGrams = sumOf { it.macros.fiberGrams ?: 0.0 },
    sugarGrams = sumOf { it.macros.sugarGrams ?: 0.0 },
)
