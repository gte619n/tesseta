package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.DrinkModeStore
import com.gte619n.healthfitness.data.nutrition.DrinkRepository
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.AlcoholInfo
import com.gte619n.healthfitness.domain.nutrition.DrinkSession
import com.gte619n.healthfitness.domain.nutrition.DrinkSessionSummary
import com.gte619n.healthfitness.domain.nutrition.DrinkTally
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.forPortion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/** Drink meal bucket (D8) — not a `Meal` enum value; drinks log only from the card. */
private const val DRINKS_MEAL = "DRINKS"

/** The long-press pour-size multipliers (D22). */
val DRINK_QUANTITIES = listOf(0.5, 1.0, 1.5, 2.0)

data class DrinkCardUiState(
    val drinkModeEnabled: Boolean = false,
    val session: DrinkSession = DrinkSession.NONE,
    /** All my drinks (from the warmed offline cache); the searchable full list. */
    val allDrinks: List<Food> = emptyList(),
    /** Recent drinks first (D12), resolved from the mirror + warmed cache. */
    val recentDrinks: List<Food> = emptyList(),
    /** The current search query over the full list. */
    val query: String = "",
    /** A daily calorie target, if set — drives the "− kcal of budget" fragment. */
    val calorieTarget: Double? = null,
    /** Calories already logged today (all meals) so the budget dent is accurate. */
    val kcalLoggedToday: Double = 0.0,
    /** A one-time End summary to show, or null. */
    val summary: DrinkSessionSummary? = null,
) {
    val tally: DrinkTally get() = DrinkTally.of(session.loggedDrinks)

    /** Search results over the full drink list (empty query ⇒ all). */
    val searchResults: List<Food>
        get() {
            val q = query.trim().lowercase()
            if (q.isBlank()) return allDrinks
            return allDrinks.filter {
                it.name.lowercase().contains(q) || (it.brand?.lowercase()?.contains(q) == true)
            }
        }
}

/**
 * IMPL-DRINK-01 (Phase 3) — the home Drink card's state + actions.
 *
 * Ties together the three device-local stores that make the card work offline:
 *  - [DrinkModeStore] — the Drink Mode flag + durable [DrinkSession] tally.
 *  - [DrinkRepository] — the warmed offline drink catalog (tiles + search).
 *  - [NutritionRepository] — the durable, offline add-entry op rail (D16) + the
 *    recents source + the daily-total/target reads for the budget line.
 *
 * A tap logs a drink INSTANTLY into the local tally (D13) and enqueues an
 * add-entry op dated to [DrinkSession.sessionDay] (back-dated across midnight, D6);
 * Undo pops the tally and deletes the entry (the outbox coalesces a create+delete
 * before send, or issues a delete after). Nothing here is synced except the entries.
 */
@HiltViewModel
class DrinkSessionViewModel @Inject constructor(
    private val store: DrinkModeStore,
    private val drinks: DrinkRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DrinkCardUiState())
    val state: StateFlow<DrinkCardUiState> = _state.asStateFlow()

    init {
        combine(store.enabled, store.session) { enabled, session ->
            enabled to session
        }.onEach { (enabled, session) ->
            _state.update { it.copy(drinkModeEnabled = enabled, session = session) }
        }.launchIn(viewModelScope)

        // IMPL-DRINK-01 (IL-13): reconcile the active session's local tally with
        // day-view deletes. A session-logged drink deleted from the day view (or on
        // another device) leaves the entries mirror; without this its LoggedDrink
        // would linger in `loggedDrinks` until the session ends, over-counting the
        // live tally. We observe the active entry-id set and, whenever a session is
        // active, drop any logged drink whose entry is gone via the store — the same
        // path Undo uses, so DrinkTally re-derives immediately. Offline-safe (pure
        // Room read) and idempotent (removeDrink is a no-op once the row is gone).
        combine(store.session, nutrition.observeActiveEntryIds()) { session, activeIds ->
            session to activeIds
        }.onEach { (session, activeIds) ->
            reconcileTally(session, activeIds).forEach { store.removeDrink(it) }
        }.launchIn(viewModelScope)
    }

    /**
     * Warm the offline drink cache + refresh recents / target. Call on Drink Mode
     * enable, session start, and app foreground (§4.3). Best-effort and offline-
     * tolerant: a failed warm keeps the last-warmed cache so tiles never vanish.
     */
    fun refresh() {
        viewModelScope.launch {
            val warmed = drinks.warm()
            val recents = resolveRecents(warmed)
            _state.update { it.copy(allDrinks = warmed, recentDrinks = recents) }
        }
        viewModelScope.launch {
            val target = runCatching { nutrition.target() }.getOrNull()
            val today = runCatching {
                nutrition.cachedDay(LocalDate.now().toString())?.totals?.caloriesKcal
            }.getOrNull()
            _state.update {
                it.copy(calorieTarget = target?.caloriesKcal, kcalLoggedToday = today ?: 0.0)
            }
        }
    }

    /** Resolve recent-drink foodIds (from the mirror) back to warmed [Food]s. */
    private suspend fun resolveRecents(warmed: List<Food>): List<Food> {
        val byId = warmed.associateBy { it.foodId }
        return runCatching { nutrition.cachedRecentDrinks() }.getOrDefault(emptyList())
            .mapNotNull { id -> byId[id] ?: drinks.cachedDrink(id) }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setDrinkMode(enabled: Boolean) {
        viewModelScope.launch {
            store.setEnabled(enabled)
            if (enabled) refresh()
        }
    }

    fun startSession() {
        viewModelScope.launch {
            store.startSession(System.currentTimeMillis(), LocalDate.now().toString())
            refresh()
        }
    }

    /**
     * Log one drink at [quantity] (D13/D22). Instant local tally append + a durable
     * add-entry op on [DrinkSession.sessionDay]; returns the entryId so the caller
     * can wire the "Added — Undo" snackbar. No-op (returns null) when no session.
     */
    fun logDrink(food: Food, quantity: Double = 1.0): String? {
        val session = _state.value.session
        if (!session.active) return null
        val entryId = UUID.randomUUID().toString()
        val plan = planDrinkLog(food, quantity, session, entryId, System.currentTimeMillis())

        viewModelScope.launch {
            // 1) Instant local tally (offline, D16).
            store.appendDrink(plan.logged)
            // 2) Durable add-entry op on the op rail (back-dated to sessionDay, D6).
            runCatching { nutrition.addEntry(plan.date, plan.request, entryId = entryId) }
        }
        return entryId
    }

    /**
     * Undo a just-logged drink (D13): pop the local tally and delete the entry. The
     * outbox coalesces the create+delete (a not-yet-sent op is cancelled); after
     * send it replays as a delete. Robust to misclicks while out.
     */
    fun undo(entryId: String) {
        val sessionDay = _state.value.session.sessionDay
        viewModelScope.launch {
            store.removeDrink(entryId)
            runCatching { nutrition.deleteEntry(sessionDay, entryId) }
        }
    }

    /**
     * End the session (D21) with a user-editable [closeTimeMillis] (default now,
     * must be ≥ start). Produces the one-time summary, then clears the session
     * (entries remain). Rejects a close time before start by clamping to start.
     */
    fun endSession(closeTimeMillis: Long) {
        val session = _state.value.session
        if (!session.active) return
        val close = closeTimeMillis.coerceAtLeast(session.startedAtMillis)
        val tally = DrinkTally.of(session.loggedDrinks)
        val summary = DrinkSessionSummary(
            count = tally.count,
            standardDrinks = tally.standardDrinks,
            kcal = tally.kcal,
            durationMillis = close - session.startedAtMillis,
        )
        viewModelScope.launch {
            store.clearSession()
            _state.update { it.copy(summary = summary) }
        }
    }

    /** Dismiss the one-time End summary. */
    fun dismissSummary() = _state.update { it.copy(summary = null) }
}

/**
 * The pure plan for logging one drink: the frozen entry request (with its
 * back-dated date) plus the local-tally row. Extracted so the D6 back-dating
 * (`date == sessionDay`) and the D22 multiplier scaling (macros × q, std × q) are
 * unit-testable without Hilt / the ViewModel scope.
 */
data class DrinkLogPlan(
    val date: String,
    val request: EntryRequest,
    val logged: DrinkSession.LoggedDrink,
)

/**
 * Build a [DrinkLogPlan] for [food] at [quantity] within [session]. The entry is
 * dated to [DrinkSession.sessionDay] — so a 01:00 drink in a Friday session lands
 * on Friday (D6) — and every macro + standard-drink figure scales linearly by
 * [quantity] (D22): `macros = macrosPer100g × (servingGrams × q)/100`,
 * `standardDrinks = default × q`.
 */
fun planDrinkLog(
    food: Food,
    quantity: Double,
    session: DrinkSession,
    entryId: String,
    atMillis: Long,
): DrinkLogPlan {
    val serving = food.servingSizes.firstOrNull()
    val servingGrams = serving?.grams ?: 100.0
    val servingLabel = serving?.label ?: "1 serving"
    val macros = food.macrosPer100g.forPortion(servingGrams, quantity)
    val std = (food.alcohol?.standardDrinks ?: 0.0) * quantity
    return DrinkLogPlan(
        date = session.sessionDay,
        request = EntryRequest(
            meal = DRINKS_MEAL,
            foodId = food.foodId,
            foodName = food.name,
            servingLabel = servingLabel,
            servingGrams = servingGrams,
            quantity = quantity,
            macros = macros,
            source = "CATALOG",
        ),
        logged = DrinkSession.LoggedDrink(
            entryId = entryId,
            foodId = food.foodId,
            name = food.name,
            standardDrinks = std,
            kcal = macros.caloriesKcal ?: 0.0,
            sugar = macros.sugarGrams ?: 0.0,
            carbs = macros.carbsGrams ?: 0.0,
            quantity = quantity,
            atMillis = atMillis,
        ),
    )
}

/**
 * IMPL-DRINK-01 (IL-13) — the pure reconcile step: which of an active session's
 * logged drinks are no longer present in the entries mirror's [activeEntryIds], and
 * so must be dropped from the tally. A drink deleted from the day view (this device
 * or another) removes its `nutritionEntries` row, so its [DrinkSession.LoggedDrink]
 * should be popped to keep the live count honest.
 *
 * A just-logged drink appends to the session's tally instantly (offline, D16) while
 * its add-entry op writes the mirror asynchronously, so for a brief window its row
 * legitimately isn't in [activeEntryIds] yet. To avoid reaping that in-flight log,
 * a drink logged within [graceMillis] of [nowMillis] is never dropped — only a
 * drink that's had time to land and then vanished counts as deleted.
 *
 * Pure + Android-free so it's unit-tested directly. Returns [] when the session is
 * inactive (a summary/ended session keeps its frozen tally) or every still-eligible
 * logged drink is present. A LoggedDrink with a blank entryId is left alone (it
 * never had a mirror row to lose).
 */
fun reconcileTally(
    session: DrinkSession,
    activeEntryIds: Set<String>,
    nowMillis: Long = System.currentTimeMillis(),
    graceMillis: Long = RECONCILE_GRACE_MILLIS,
): List<String> {
    if (!session.active) return emptyList()
    return session.loggedDrinks
        .filter { it.entryId.isNotBlank() }
        .filter { nowMillis - it.atMillis >= graceMillis }
        .map { it.entryId }
        .filter { it !in activeEntryIds }
        .distinct()
}

/**
 * Settle window before a just-logged drink is eligible for delete-reconcile — long
 * enough for the durable add-entry op to write the mirror, short enough that a real
 * day-view delete decrements the tally promptly.
 */
const val RECONCILE_GRACE_MILLIS = 4_000L

/** The alcohol snapshot scaled by [quantity] (D22) — for display / freezing. */
fun AlcoholInfo.scaled(quantity: Double): AlcoholInfo = AlcoholInfo(
    abvPercent = abvPercent,
    servingVolumeMl = servingVolumeMl?.let { it * quantity },
    alcoholGrams = alcoholGrams?.let { it * quantity },
    standardDrinks = standardDrinks?.let { it * quantity },
)
