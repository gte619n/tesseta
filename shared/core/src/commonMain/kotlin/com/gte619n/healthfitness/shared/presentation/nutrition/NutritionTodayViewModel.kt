package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.EntryPatchRequest
import com.gte619n.healthfitness.shared.data.EntryRequest
import com.gte619n.healthfitness.shared.data.Food
import com.gte619n.healthfitness.shared.data.MealSearchResult
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import com.gte619n.healthfitness.shared.data.NutritionOp
import com.gte619n.healthfitness.shared.data.NutritionOpQueue
import com.gte619n.healthfitness.shared.data.NutritionOpType
import com.gte619n.healthfitness.shared.domain.nutrition.Entry
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import com.gte619n.healthfitness.shared.domain.nutrition.MealGroup
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.domain.nutrition.forPortion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Id prefix of the synthetic rows shown while a durable nutrition op runs.
 *  Mirrors Android's `PENDING_CAPTURE_PREFIX`. */
const val PENDING_CAPTURE_PREFIX = "pending-capture-"

/**
 * IMPL-IOS-01 Phase 3 Wave C — the shared Today-page ViewModel. Port of the
 * Android `feature-nutrition/NutritionTodayViewModel`, following the reference
 * `MedicationsViewModel` shape (androidx.lifecycle.ViewModel, StateFlow of a
 * single UI-state data class, suspend intents on `viewModelScope`).
 *
 * Offline-first: [startDayObserve] renders the reactive mirror stream; [load]
 * seeds instantly from `cachedDay` (no spinner when cached) then revalidates via
 * `day()`. In-flight durable ops are projected as synthetic rows by the pure
 * [withPendingOps] merge — identical logic to Android so the two clients show the
 * same "logging…" rows for the same ops.
 */
data class NutritionTodayUiState(
    val loading: Boolean = true,
    val date: String,
    val day: NutritionDay? = null,
    val error: String? = null,
    val pendingEntryIds: Set<String> = emptySet(),
    val savingEdit: Boolean = false,
    val savingIngredient: Boolean = false,
    val savingAdjust: Boolean = false,
    val savingLeftover: Boolean = false,
    val isRefreshing: Boolean = false,
    /** In-flight durable ops; projected as synthetic rows onto [day]. */
    val pendingOps: List<NutritionOp> = emptyList(),
    /** The entry (id) whose adjust review sheet is open, or null. */
    val reviewingAdjustId: String? = null,
    /** The entry (id) whose leftover review sheet is open, or null. */
    val reviewingLeftoverId: String? = null,
    /** The entry (id) whose adjust proposal just landed foreground → banner. */
    val adjustReviewBannerId: String? = null,
) {
    /** The day with the in-flight ops merged in (what the screen renders). */
    val displayDay: NutritionDay? get() = day.withPendingOps(pendingOps, date)
}

class NutritionTodayViewModel(
    private val repository: NutritionDayRepository,
    private val ops: NutritionOpQueue,
    initialDate: String,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionTodayUiState(date = initialDate))
    val state: StateFlow<NutritionTodayUiState> = _state.asStateFlow()

    private var dayJob: Job? = null
    private var observedDate: String? = null
    /** Entry ids we've already banner-ed (once each), so it never nags. */
    private var bannerShownAdjustIds: Set<String> = emptySet()

    init {
        // Mirror the in-flight durable ops into state. When one completes (the
        // list shrinks) the worker has pulled the real entry into the mirror —
        // re-load so the synthetic row swaps for the real one.
        viewModelScope.launch {
            var previous = emptyList<NutritionOp>()
            ops.observeAll().collect { list ->
                val completed = previous.size > list.size
                previous = list
                _state.update { it.copy(pendingOps = list) }
                if (completed) refresh()
            }
        }
    }

    /**
     * (Re)subscribe the reactive day stream for [date]. Emits the mirror-assembled
     * day immediately and on every subsequent change. Guarded on the shown date so
     * a late emission for a navigated-away day is ignored.
     */
    private fun startDayObserve(date: String) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            repository.observeDay(date).collect { day ->
                if (_state.value.date == date) {
                    _state.update { it.copy(day = day, loading = false, adjustReviewBannerId = bannerFor(day)) }
                }
            }
        }
    }

    /**
     * The entry id to surface in the foreground "adjustment ready" banner: the first
     * PENDING_REVIEW entry we haven't banner-ed yet, and only while its review sheet
     * isn't open. Returns the current banner otherwise so a mere re-emit doesn't
     * clear it. Each entry banners at most once.
     */
    private fun bannerFor(day: NutritionDay?): String? {
        val open = _state.value.reviewingAdjustId != null
        val fresh = day?.meals?.flatMap { it.entries }
            ?.firstOrNull { it.entryId !in bannerShownAdjustIds && isPendingAdjust(it) }
        if (fresh != null && !open) {
            bannerShownAdjustIds = bannerShownAdjustIds + fresh.entryId
            return fresh.entryId
        }
        return _state.value.adjustReviewBannerId
    }

    // A pending-adjust op decorating this entry means a review will land; used only
    // for the banner heuristic (the authoritative state comes from the mirror).
    private fun isPendingAdjust(entry: Entry): Boolean =
        _state.value.pendingOps.any {
            it.type == NutritionOpType.ADJUST_MEAL && it.targetEntryId == entry.entryId
        }

    fun previousDay() = load(minusOneDay(_state.value.date))
    fun nextDay() = load(plusOneDay(_state.value.date))
    fun refresh() = load(_state.value.date)

    fun onPullRefresh() {
        _state.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            runCatching { repository.refreshDay(_state.value.date) }
            load(_state.value.date)
        }
    }

    private fun load(date: String) {
        val sameDateShown = _state.value.day != null && _state.value.date == date
        if (observedDate != date) {
            observedDate = date
            startDayObserve(date)
        }
        viewModelScope.launch {
            if (!sameDateShown) {
                val cached = runCatching { repository.cachedDay(date) }.getOrNull()
                _state.update { it.copy(date = date, day = cached, loading = cached == null, error = null) }
            } else {
                _state.update { it.copy(date = date, error = null) }
            }
            try {
                val day = repository.day(date)
                _state.update { it.copy(loading = false, isRefreshing = false, day = day, error = null) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        isRefreshing = false,
                        error = if (it.day == null) (e.message ?: "Failed to load nutrition") else null,
                    )
                }
            }
        }
    }

    // ---- Entry mutations --------------------------------------------------

    fun deleteEntry(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
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
                    it.copy(pendingEntryIds = it.pendingEntryIds - entryId, error = e.message ?: "Delete failed")
                }
            }
        }
    }

    /** Move an entry to another meal (drag-and-drop): optimistic hop, PATCH, reload. */
    fun moveEntry(entryId: String, targetMeal: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val current = _state.value.day ?: return
        val source = current.meals.firstOrNull { g -> g.entries.any { it.entryId == entryId } } ?: return
        if (source.meal == targetMeal) return
        val entry = source.entries.first { it.entryId == entryId }
        _state.update { it.copy(day = current.withEntryMoved(entry, targetMeal)) }
        val date = _state.value.date
        viewModelScope.launch {
            try {
                repository.patchEntry(date, entryId, EntryPatchRequest(meal = targetMeal))
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(day = current, error = e.message ?: "Move failed") }
            }
        }
    }

    fun updateEntry(entryId: String, patch: EntryPatchRequest) {
        val date = _state.value.date
        _state.update { it.copy(savingEdit = true) }
        viewModelScope.launch {
            try {
                repository.patchEntry(date, entryId, patch)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingEdit = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingEdit = false, error = e.message ?: "Update failed") }
            }
        }
    }

    /** Log a catalog food (chosen serving + quantity); macros snapshotted here. */
    fun addCatalogEntry(meal: Meal, food: Food, servingIndex: Int, quantity: Double) {
        val serving = food.servingSizes.getOrNull(servingIndex) ?: return
        val macros = food.macrosPer100g.forPortion(serving.grams, quantity)
        submit(
            EntryRequest(
                meal = meal.wire,
                foodId = food.foodId,
                foodName = food.name,
                servingLabel = serving.label,
                servingGrams = serving.grams,
                quantity = quantity,
                macros = macros,
                source = "CATALOG",
            ),
        )
    }

    /** Quick ad-hoc entry: raw macros, no catalog food. */
    fun addQuickEntry(meal: Meal, name: String, macros: Macros) {
        submit(
            EntryRequest(
                meal = meal.wire,
                foodId = null,
                foodName = name,
                servingLabel = "1 serving",
                servingGrams = 100.0,
                quantity = 1.0,
                macros = macros,
                source = "MANUAL",
            ),
        )
    }

    private fun submit(body: EntryRequest) {
        val date = _state.value.date
        viewModelScope.launch {
            try {
                repository.addEntry(date, body)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }

    /** Fire-and-forget describe: server logs an ANALYZING placeholder; the op-rail
     *  projects a synthetic row until it lands. */
    fun describeMealAsync(meal: Meal, description: String) {
        val text = description.trim()
        if (text.isBlank()) return
        val date = _state.value.date
        viewModelScope.launch {
            runCatching { repository.describeMealAsync(date, text, meal.wire) }
        }
    }

    /** One-tap re-log of a recent entry onto [meal] (server-side copy, no AI wait). */
    fun relogRecent(meal: Meal, entry: Entry) {
        val date = _state.value.date
        viewModelScope.launch {
            try {
                val created = repository.relog(date, entry, meal.wire)
                _state.update {
                    it.copy(day = it.day.withEntryAppended(created.copy(syncState = "PENDING"), date), error = null)
                }
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Add failed") }
            }
        }
    }

    /** Log a saved meal by id onto [meal] (durable op → synthetic row). */
    fun logSavedMeal(meal: Meal, result: MealSearchResult) {
        val date = _state.value.date
        viewModelScope.launch {
            runCatching {
                repository.logDescribedMeal(
                    date, result.mealId, meal.wire,
                    label = result.name,
                    knownImageUrl = result.imageUrl,
                    knownImageStatus = result.imageStatus,
                )
            }.onFailure { e -> _state.update { it.copy(error = e.message ?: "Add failed") } }
        }
    }

    // ---- Composite meal editing ------------------------------------------

    fun saveCompositeMeal(entryId: String, title: String, portion: Double, quantities: List<Double>) {
        val date = _state.value.date
        _state.update { it.copy(savingIngredient = true) }
        viewModelScope.launch {
            try {
                repository.updateComposite(
                    date = date,
                    entryId = entryId,
                    title = title,
                    portion = portion.takeIf { it > 0 } ?: 1.0,
                    quantities = quantities,
                )
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingIngredient = false, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingIngredient = false, error = e.message ?: "Save failed") }
            }
        }
    }

    private var lastRemoved: NutritionDayRepository.RemovedIngredient? = null

    fun removeIngredient(entryId: String, index: Int) {
        val date = _state.value.date
        viewModelScope.launch {
            try {
                lastRemoved = repository.removeIngredient(date, entryId, index)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't remove the ingredient") }
            }
        }
    }

    fun undoRemoveIngredient() {
        val removed = lastRemoved ?: return
        lastRemoved = null
        val date = _state.value.date
        viewModelScope.launch {
            try {
                repository.restoreIngredient(date, removed.entry.entryId, removed.index, removed.ingredient)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't restore the ingredient") }
            }
        }
    }

    // ---- Adjust with AI (async) ------------------------------------------

    fun submitAdjust(entryId: String, instruction: String, saveAsMeal: Boolean) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        viewModelScope.launch {
            runCatching { repository.submitAdjust(date, entryId, instruction, saveAsMeal) }
        }
    }

    fun reviewAdjust(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update { it.copy(reviewingAdjustId = entryId, adjustReviewBannerId = null) }
    }

    fun closeAdjustReview() = _state.update { it.copy(reviewingAdjustId = null) }
    fun dismissAdjustBanner() = _state.update { it.copy(adjustReviewBannerId = null) }

    fun commitAdjust(entryId: String, saveAsMeal: Boolean) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        _state.update { it.copy(savingAdjust = true) }
        viewModelScope.launch {
            try {
                repository.commitAdjust(date, entryId, saveAsMeal)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingAdjust = false, reviewingAdjustId = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingAdjust = false, error = e.message ?: "Couldn't adjust the meal") }
            }
        }
    }

    fun discardAdjust(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        _state.update { it.copy(savingAdjust = true) }
        viewModelScope.launch {
            try {
                repository.discardAdjust(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingAdjust = false, reviewingAdjustId = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingAdjust = false, error = e.message ?: "Couldn't discard the adjustment") }
            }
        }
    }

    /** Deep-link target of the FCM "adjust-review" notification tap. */
    fun openAdjustReviewFor(dateStr: String, entryId: String) {
        if (_state.value.date != dateStr) load(dateStr)
        _state.update { it.copy(reviewingAdjustId = entryId, adjustReviewBannerId = null) }
    }

    // ---- Remove leftovers -------------------------------------------------

    fun reviewLeftovers(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update { it.copy(reviewingLeftoverId = entryId) }
    }

    fun closeLeftoverReview() = _state.update { it.copy(reviewingLeftoverId = null) }

    fun applyLeftovers(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        _state.update { it.copy(savingLeftover = true) }
        viewModelScope.launch {
            try {
                repository.applyLeftovers(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingLeftover = false, reviewingLeftoverId = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingLeftover = false, error = e.message ?: "Couldn't apply leftovers") }
            }
        }
    }

    fun discardLeftovers(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        _state.update { it.copy(savingLeftover = true) }
        viewModelScope.launch {
            try {
                repository.discardLeftovers(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingLeftover = false, reviewingLeftoverId = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingLeftover = false, error = e.message ?: "Couldn't discard leftovers") }
            }
        }
    }

    fun restoreFullPortion(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        _state.update { it.copy(savingLeftover = true) }
        viewModelScope.launch {
            try {
                repository.restoreLeftovers(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, savingLeftover = false, reviewingLeftoverId = null, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(savingLeftover = false, error = e.message ?: "Couldn't restore the portion") }
            }
        }
    }

    /** Deep-link target of the FCM "leftover-review" notification tap. */
    fun openLeftoverReviewFor(dateStr: String, entryId: String) {
        if (_state.value.date != dateStr) load(dateStr)
        _state.update { it.copy(reviewingLeftoverId = entryId) }
    }

    // ---- Image self-heal --------------------------------------------------

    fun regenerateEntryImage(entryId: String) {
        val date = _state.value.date
        viewModelScope.launch {
            try {
                repository.regenerateEntryImage(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't retry the image") }
            }
        }
    }

    fun reanalyzeEntry(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date
        viewModelScope.launch {
            try {
                repository.reanalyzeEntry(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't retry the photo") }
            }
        }
    }

    suspend fun servingHint(entryId: String): String? {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return null
        return repository.servingHint(_state.value.date, entryId)
    }
}

// ---------------------------------------------------------------------------
// Pure day transforms — ported VERBATIM from Android NutritionTodayViewModel.kt
// so both clients project pending ops / optimistic moves identically. These are
// what commonTest exercises.
// ---------------------------------------------------------------------------

/**
 * Merge the in-flight durable ops into the day for display: one synthetic
 * "logging…" row (ANALYZING, zero macros) per op targeting [date], appended to its
 * target meal group. Pure presentation — totals are untouched (a pending op
 * contributes nothing yet). REMOVE_LEFTOVERS / ADJUST_MEAL ops decorate an
 * EXISTING entry (via `targetEntryId`) rather than adding a row; that decoration
 * is carried in the VM's [EntryState] map, so here they simply add no synthetic
 * row (matching Android's filter).
 */
fun NutritionDay?.withPendingOps(ops: List<NutritionOp>, date: String): NutritionDay? {
    val forDate = ops.filter { it.date == date }
    if (forDate.isEmpty()) return this
    val base = this ?: NutritionDay(date = date, totals = Macros.EMPTY)
    var meals = base.meals
    forDate.filterNot {
        it.type == NutritionOpType.REMOVE_LEFTOVERS || it.type == NutritionOpType.ADJUST_MEAL
    }.forEach { op ->
        val isCapture = op.type == NutritionOpType.CAPTURE_PHOTO
        val synthetic = Entry(
            entryId = PENDING_CAPTURE_PREFIX + op.id,
            meal = op.mealWire,
            foodName = if (isCapture) "New photo" else op.label,
            quantity = 1.0,
            macros = Macros.EMPTY,
            source = "PHOTO",
            analysisStatus = "ANALYZING",
        )
        meals = if (meals.any { it.meal == op.mealWire }) {
            meals.map { g -> if (g.meal == op.mealWire) g.copy(entries = g.entries + synthetic) else g }
        } else {
            meals + MealGroup(meal = op.mealWire, subtotal = Macros.EMPTY, entries = listOf(synthetic))
        }
    }
    return base.copy(meals = meals)
}

/** Return a copy of this day with [entry] moved into [targetMeal], both subtotals re-summed. */
fun NutritionDay.withEntryMoved(entry: Entry, targetMeal: String): NutritionDay {
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

/** Return a copy with [entry] appended to its meal group, subtotal + totals re-summed. */
fun NutritionDay?.withEntryAppended(entry: Entry, date: String): NutritionDay {
    val base = this ?: NutritionDay(date = date, totals = Macros.EMPTY)
    val hasGroup = base.meals.any { it.meal.equals(entry.meal, ignoreCase = true) }
    val meals = if (hasGroup) {
        base.meals.map { g ->
            if (g.meal.equals(entry.meal, ignoreCase = true)) {
                val entries = g.entries + entry
                g.copy(entries = entries, subtotal = entries.sumMacros())
            } else {
                g
            }
        }
    } else {
        base.meals + MealGroup(meal = entry.meal, subtotal = listOf(entry).sumMacros(), entries = listOf(entry))
    }
    return base.copy(meals = meals, totals = meals.flatMap { it.entries }.sumMacros())
}

/** Sum a list of entries' macros into a single subtotal snapshot. */
fun List<Entry>.sumMacros(): Macros = Macros(
    caloriesKcal = sumOf { it.macros.caloriesKcal ?: 0.0 },
    proteinGrams = sumOf { it.macros.proteinGrams ?: 0.0 },
    carbsGrams = sumOf { it.macros.carbsGrams ?: 0.0 },
    fatGrams = sumOf { it.macros.fatGrams ?: 0.0 },
    fiberGrams = sumOf { it.macros.fiberGrams ?: 0.0 },
    sugarGrams = sumOf { it.macros.sugarGrams ?: 0.0 },
)

// ---- Local date arithmetic (ISO yyyy-MM-dd, no java.time in commonMain) -----

private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
private fun daysInMonth(y: Int, m: Int): Int = when (m) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (isLeap(y)) 29 else 28
}
private fun pad2(v: Int) = if (v < 10) "0$v" else "$v"

internal fun plusOneDay(iso: String): String {
    val (y, m, d) = iso.split("-").map { it.toInt() }
    return if (d < daysInMonth(y, m)) "$y-${pad2(m)}-${pad2(d + 1)}"
    else if (m < 12) "$y-${pad2(m + 1)}-01"
    else "${y + 1}-01-01"
}

internal fun minusOneDay(iso: String): String {
    val (y, m, d) = iso.split("-").map { it.toInt() }
    return if (d > 1) "$y-${pad2(m)}-${pad2(d - 1)}"
    else if (m > 1) "$y-${pad2(m - 1)}-${pad2(daysInMonth(y, m - 1))}"
    else "${y - 1}-12-31"
}
