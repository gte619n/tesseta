package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import com.gte619n.healthfitness.data.db.entity.NutritionOpType
import com.gte619n.healthfitness.data.nutrition.NutritionOpStore
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.domain.nutrition.AdjustApplyRequest
import com.gte619n.healthfitness.domain.nutrition.AdjustPreviewResponse
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
// can take a couple of minutes. The backend does push an FCM wakeup when the
// entry finalizes AND when its image lands (FoodEntryImageService), but a
// data push can be delayed or dropped, so a FOREGROUND screen must not depend
// on it — a slow generation past a short cap left the row stuck on its loader
// forever ("generated on web, never popped in on mobile"). The settle-poll
// therefore keeps converging on its own for a generous budget while the screen
// is open, backing off so a long generation isn't hammered; the push and the
// on-resume refresh remain the path for anything still pending after that.
private const val SETTLE_POLL_BUDGET_MILLIS = 4 * 60 * 1000L // keep converging up to ~4 min
private const val SETTLE_POLL_MIN_DELAY_MILLIS = 2_500L // fast first, for snappy early feedback
private const val SETTLE_POLL_MAX_DELAY_MILLIS = 10_000L // then ease off while it keeps generating

// A run of consecutive failed poll fetches (offline, or a persistent server
// error) stops the settle-poll — but a single flaky fetch does NOT count against
// the generation-settle budget above, so a spotty connection keeps retrying
// instead of giving up with the image still generating. The poll re-arms on the
// next resume / nutrition push regardless.
private const val MAX_SETTLE_POLL_FAILURES = 8

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
    /** true while an accepted AI adjustment is being applied to an entry. */
    val savingAdjust: Boolean = false,
    /** true while a user-initiated pull-to-refresh is in flight. */
    val isRefreshing: Boolean = false,
    /**
     * In-flight durable nutrition ops (photo capture, describe, saved-meal,
     * label / meal-items confirm). The screen renders one synthetic "logging…"
     * row per op, in the meal group it targets, until the real entry lands. These
     * survive process death (they're Room-backed), unlike the old in-memory rows.
     */
    val pendingOps: List<NutritionOpEntity> = emptyList(),
)

@HiltViewModel
class NutritionTodayViewModel @Inject constructor(
    private val repository: NutritionRepository,
    pendingOps: NutritionOpStore,
    syncSignals: SyncSignals,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionTodayUiState())
    val state: StateFlow<NutritionTodayUiState> = _state.asStateFlow()

    // Polls the day while any entry's image is still generating, so freshly
    // logged foods swap their placeholder for the studio image without the user
    // having to leave and return. Cancelled/replaced on each load.
    private var imagePollJob: Job? = null

    // State-mgmt: the reactive source of truth for the shown day. Observes the
    // entries + target mirror (and the capture-preview store) so a local write, a
    // SyncEngine pull, or a just-captured photo reflects immediately — closing the
    // gap that used to make nutrition rely purely on imperative REST refetches.
    // The load/refresh/settle-poll paths remain as the network revalidation that
    // fills the mirror; this observer renders whatever lands there. Re-subscribed
    // when the shown date changes.
    private var dayJob: Job? = null
    private var observedDate: LocalDate? = null

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
        // Mirror the in-flight durable ops into state. When one completes (the
        // list shrinks) the worker has already pulled the server's real entry into
        // the mirror — re-load so the synthetic row swaps for the real one and the
        // settle-poll engages.
        viewModelScope.launch {
            var previous = emptyList<NutritionOpEntity>()
            pendingOps.observeAll().collect { list ->
                val completed = previous.size > list.size
                previous = list
                _state.update { it.copy(pendingOps = list) }
                if (completed) refresh()
            }
        }
        // (The just-captured-photo overlay and mirror reactivity are now handled by
        // the reactive [startDayObserve] stream, which includes the capture-preview
        // store in its assembly.)
    }

    /**
     * (Re)subscribe the reactive day stream for [date]. Emits the mirror-assembled
     * day immediately and on every subsequent mirror / capture-preview change, so
     * the screen reflects local writes and background syncs without an imperative
     * refetch. Guarded on the shown date so a late emission for a day the user
     * navigated away from is ignored.
     */
    private fun startDayObserve(date: LocalDate) {
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            repository.observeDay(date.format(ISO_DATE)).collect { day ->
                if (_state.value.date == date) {
                    _state.update { it.copy(day = day, loading = false) }
                }
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

    /**
     * Adjust with AI — preview: run a free-text correction against the entry and
     * return the revised meal as a proposal. Suspends for the model round-trip and
     * throws on failure so the sheet can surface the error; nothing is persisted.
     */
    suspend fun previewAdjustment(entryId: String, instruction: String): AdjustPreviewResponse {
        return repository.adjustPreview(_state.value.date.format(ISO_DATE), entryId, instruction)
    }

    /**
     * Adjust with AI — apply the accepted proposal onto the entry, then reload and
     * close the sheet. Runs on the ViewModel scope (not the sheet's) so closing the
     * sheet can't cancel the in-flight apply. A composite meal's image regenerates
     * server-side, so the settle-poll swaps it in.
     */
    fun applyAdjustment(entryId: String, request: AdjustApplyRequest) {
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(savingAdjust = true) }
        viewModelScope.launch {
            try {
                repository.adjustApply(date, entryId, request)
                val day = repository.day(date)
                _state.update {
                    it.copy(
                        day = day,
                        savingAdjust = false,
                        editingEntry = null,
                        editingComposite = null,
                        error = null,
                    )
                }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(savingAdjust = false, error = e.message ?: "Couldn't adjust the meal") }
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
        // Point the reactive day stream at the shown date (only re-subscribe when
        // it actually changes). It renders the mirror instantly and on every write.
        if (observedDate != date) {
            observedDate = date
            startDayObserve(date)
        }
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
                it.imageStatus == "PENDING" || it.isAnalyzing || it.syncState == "PENDING" ||
                    // A missing-but-expected image keeps the poll alive so the
                    // server's self-heal (which flips it PENDING on the next read)
                    // converges the picture without the user tapping retry.
                    it.isImageMissing
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
            val startedAt = System.currentTimeMillis()
            var delayMillis = SETTLE_POLL_MIN_DELAY_MILLIS
            var consecutiveFailures = 0
            while (System.currentTimeMillis() - startedAt < SETTLE_POLL_BUDGET_MILLIS &&
                consecutiveFailures < MAX_SETTLE_POLL_FAILURES &&
                _state.value.date == date &&
                _state.value.day.hasGeneratingImage()
            ) {
                delay(delayMillis)
                if (_state.value.date != date) return@launch
                val day = runCatching { repository.day(date.format(ISO_DATE)) }.getOrNull()
                if (day == null) {
                    // A flaky fetch must not burn the budget faster: keep the
                    // last-known day on screen and retry, giving up only after a run
                    // of consecutive failures (offline / persistent server error).
                    consecutiveFailures++
                    continue
                }
                consecutiveFailures = 0
                if (_state.value.date == date) {
                    _state.update { it.copy(day = day) }
                }
                // Ease off so a multi-minute generation isn't re-fetched every 2.5s.
                delayMillis = (delayMillis + 2_500L).coerceAtMost(SETTLE_POLL_MAX_DELAY_MILLIS)
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

    /**
     * Retry a FAILED photo analysis: the backend re-runs the analysis from the
     * photo it stored at capture time (no re-upload). Refresh so the row swaps to
     * the analyzing state, then poll for the finalized entry.
     */
    fun reanalyzeEntry(entryId: String) {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return
        val date = _state.value.date.format(ISO_DATE)
        viewModelScope.launch {
            try {
                repository.reanalyzeEntry(date, entryId)
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                pollWhileImagesGenerate(_state.value.date)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Couldn't retry the photo") }
            }
        }
    }

    /**
     * Lazy "typical serving" hint for the edit sheet, fetched when the sheet opens.
     * Suspends and returns null on any failure, so the sheet simply shows nothing.
     * A synthetic in-flight row (no server entry yet) has no hint.
     */
    suspend fun servingHint(entryId: String): String? {
        if (entryId.startsWith(PENDING_CAPTURE_PREFIX)) return null
        return repository.servingHint(_state.value.date.format(ISO_DATE), entryId)
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
            // Durable: enqueues a DESCRIBE_ASYNC op that survives process death. A
            // synthetic row appears via the pendingOps flow; when the worker's POST
            // lands the op clears and the flow re-loads the real ANALYZING entry.
            try {
                repository.describeMealAsync(date, text, meal.wire)
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
        val date = _state.value.date.format(ISO_DATE)
        _state.update { it.copy(addSheetOpen = false) }
        viewModelScope.launch {
            try {
                val created = repository.relog(date, entry, meal.wire)
                // Snap the row in immediately (like moveEntry) instead of waiting on
                // day()'s Room re-read / network re-pull — that round-trip was the
                // lag. Then revalidate in the background to reconcile totals + badge.
                _state.update {
                    it.copy(
                        day = it.day.withEntryAppended(created.copy(syncState = "PENDING"), date),
                        error = null,
                    )
                }
                val day = repository.day(date)
                _state.update { it.copy(day = day, error = null) }
                pollWhileImagesGenerate(_state.value.date)
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
            // Durable: enqueues a LOG_SAVED_MEAL op. The synthetic row (and, on
            // completion, the real entry) come through the pendingOps flow.
            try {
                repository.logDescribedMeal(
                    date, result.mealId, meal.wire,
                    label = result.name,
                    knownImageUrl = result.imageUrl,
                    knownImageStatus = result.imageStatus,
                )
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

/** Id prefix of the synthetic rows shown while a durable nutrition op runs. */
const val PENDING_CAPTURE_PREFIX = "pending-capture-"

/**
 * Merge the in-flight durable ops into the day for display: one synthetic
 * "logging…" row (ANALYZING, zero macros) per op targeting [date], appended to
 * its target meal group. Pure presentation — totals are untouched (a pending op
 * contributes nothing yet, same as a server placeholder). These rows survive
 * process death because the ops are Room-backed, not in-memory.
 */
fun NutritionDay?.withPendingOps(
    ops: List<NutritionOpEntity>,
    date: LocalDate,
): NutritionDay? {
    val forDate = ops.filter { it.date == date.format(ISO_DATE) }
    if (forDate.isEmpty()) return this
    val base = this ?: NutritionDay(date = date.format(ISO_DATE), totals = Macros.EMPTY)
    var meals = base.meals
    forDate.forEach { op ->
        val isCapture = op.type == NutritionOpType.CAPTURE_PHOTO.name
        val synthetic = Entry(
            entryId = PENDING_CAPTURE_PREFIX + op.id,
            meal = op.mealWire,
            foodName = if (isCapture) "New photo" else op.label,
            quantity = 1.0,
            macros = Macros.EMPTY,
            source = "PHOTO",
            analysisStatus = "ANALYZING",
            // The just-captured JPEG shows as the thumbnail (with a loader) while
            // the upload runs; non-photo ops (describe/saved-meal) get the spinner.
            localImagePath = if (isCapture) op.jpegPath else null,
        )
        meals = if (meals.any { it.meal == op.mealWire }) {
            meals.map { g ->
                if (g.meal == op.mealWire) g.copy(entries = g.entries + synthetic) else g
            }
        } else {
            meals + MealGroup(meal = op.mealWire, subtotal = Macros.EMPTY, entries = listOf(synthetic))
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

/**
 * Return a copy of this day with [entry] appended to its meal group (created if
 * absent), with that group's subtotal and the day totals re-summed. Used for the
 * optimistic one-tap re-log so the row appears instantly, before the background
 * `day()` revalidation reconciles it.
 */
private fun NutritionDay?.withEntryAppended(entry: Entry, date: String): NutritionDay {
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
private fun List<Entry>.sumMacros(): Macros = Macros(
    caloriesKcal = sumOf { it.macros.caloriesKcal ?: 0.0 },
    proteinGrams = sumOf { it.macros.proteinGrams ?: 0.0 },
    carbsGrams = sumOf { it.macros.carbsGrams ?: 0.0 },
    fatGrams = sumOf { it.macros.fatGrams ?: 0.0 },
    fiberGrams = sumOf { it.macros.fiberGrams ?: 0.0 },
    sugarGrams = sumOf { it.macros.sugarGrams ?: 0.0 },
)
