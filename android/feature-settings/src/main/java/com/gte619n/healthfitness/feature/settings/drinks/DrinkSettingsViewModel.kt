package com.gte619n.healthfitness.feature.settings.drinks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.DrinkRepository
import com.gte619n.healthfitness.domain.nutrition.DrinkProposal
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-DRINK-01 — phone-side drink MANAGEMENT (Settings › Drinks), mirroring the
 * web `me/drinks` page: list my drinks, add one at a time (AI analyze → review →
 * save), edit, regenerate the image, and archive.
 *
 * The list is served live from [DrinkRepository.listMyDrinks] (which also re-warms
 * the offline cache the Drink card reads). While any drink's image is PENDING the
 * VM polls the list every few seconds so a generated glass photo resolves in place
 * without the user leaving the screen.
 */
@HiltViewModel
class DrinkSettingsViewModel @Inject constructor(
    private val repo: DrinkRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val drinks: List<Food> = emptyList(),
        val error: String? = null,
        // The add/edit sheet, or null when closed.
        val editor: EditorState? = null,
        // A transient toast-style message (e.g. "Regenerating image…").
        val message: String? = null,
    )

    /**
     * The add/edit form. [drinkId] null ⇒ creating; non-null ⇒ editing that drink.
     * All numeric fields are held as raw strings so the user can type freely; the
     * VM parses on save. [derived] mirrors the last analyze/edit alcohol readout.
     */
    data class EditorState(
        val drinkId: String? = null,
        val name: String = "",
        val abvPercent: String = "",
        val servingVolumeMl: String = "",
        val servingLabel: String = "",
        val carbsGrams: String = "",
        val sugarGrams: String = "",
        val analyzing: Boolean = false,
        val saving: Boolean = false,
        // Set once analyze returns 422 so the form can say "enter manually".
        val analyzeUnavailable: Boolean = false,
        val error: String? = null,
        // Read-only derived readouts from the last proposal (analyze) or the drink
        // being edited: alcohol grams, standard drinks, total per-serving calories.
        val derivedAlcoholGrams: Double? = null,
        val derivedStandardDrinks: Double? = null,
        val derivedCaloriesKcal: Double? = null,
    ) {
        /** ABV% + volume are required to save (alcohol-only, numbers only). */
        val canSave: Boolean
            get() = !saving && name.isNotBlank() &&
                abvPercent.toDoubleOrNull() != null &&
                servingVolumeMl.toDoubleOrNull() != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.drinks.isEmpty(), error = null) }
            runCatching { repo.listMyDrinks() }.fold(
                onSuccess = { drinks ->
                    _state.update { it.copy(loading = false, drinks = drinks, error = null) }
                    schedulePollIfPending(drinks)
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.drinks.isEmpty()) (e.message ?: "Failed to load drinks") else null,
                        )
                    }
                },
            )
        }
    }

    /** Poll while any drink image is still PENDING so it resolves to READY in place. */
    private fun schedulePollIfPending(drinks: List<Food>) {
        val anyPending = drinks.any { it.imageStatus == "PENDING" }
        if (!anyPending) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                val refreshed = runCatching { repo.listMyDrinks() }.getOrNull() ?: continue
                _state.update { it.copy(drinks = refreshed) }
                if (refreshed.none { d -> d.imageStatus == "PENDING" }) break
            }
            pollJob = null
        }
    }

    // ---- editor ----

    fun openAdd() {
        _state.update { it.copy(editor = EditorState()) }
    }

    fun openEdit(drink: Food) {
        val a = drink.alcohol
        val serving = drink.servingMacros
        _state.update {
            it.copy(
                editor = EditorState(
                    drinkId = drink.foodId,
                    name = drink.name,
                    abvPercent = a?.abvPercent?.let(::trimNumber) ?: "",
                    servingVolumeMl = a?.servingVolumeMl?.let(::trimNumber) ?: "",
                    servingLabel = drink.servingSizes.firstOrNull()?.label ?: "",
                    // Prefill mixer macros from the drink's per-serving macros (do NOT
                    // re-derive) so an edit preserves what the user last saved.
                    carbsGrams = serving?.carbsGrams?.let(::trimNumber) ?: "",
                    sugarGrams = serving?.sugarGrams?.let(::trimNumber) ?: "",
                    derivedAlcoholGrams = a?.alcoholGrams,
                    derivedStandardDrinks = a?.standardDrinks,
                    derivedCaloriesKcal = serving?.caloriesKcal,
                ),
            )
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        _state.update { s -> s.editor?.let { s.copy(editor = transform(it)) } ?: s }
    }

    /** "Analyze with AI" — fill the form from a proposal, or fall back to manual on 422. */
    fun analyze() {
        val editor = _state.value.editor ?: return
        if (editor.name.isBlank()) return
        updateEditor { it.copy(analyzing = true, error = null, analyzeUnavailable = false) }
        viewModelScope.launch {
            when (val result = repo.analyze(editor.name.trim())) {
                is DrinkRepository.AnalyzeResult.Success -> applyProposal(result.proposal)
                is DrinkRepository.AnalyzeResult.Unavailable ->
                    updateEditor { it.copy(analyzing = false, analyzeUnavailable = true) }
                is DrinkRepository.AnalyzeResult.Error ->
                    updateEditor {
                        it.copy(
                            analyzing = false,
                            error = result.cause.message ?: "Couldn't analyze — enter manually",
                        )
                    }
            }
        }
    }

    private fun applyProposal(p: DrinkProposal) {
        updateEditor {
            it.copy(
                analyzing = false,
                analyzeUnavailable = false,
                error = null,
                name = p.name.ifBlank { it.name },
                abvPercent = p.abvPercent?.let(::trimNumber) ?: it.abvPercent,
                servingVolumeMl = p.servingVolumeMl?.let(::trimNumber) ?: it.servingVolumeMl,
                carbsGrams = p.servingMacros?.carbsGrams?.let(::trimNumber) ?: it.carbsGrams,
                sugarGrams = p.servingMacros?.sugarGrams?.let(::trimNumber) ?: it.sugarGrams,
                derivedAlcoholGrams = p.alcohol?.alcoholGrams,
                derivedStandardDrinks = p.alcohol?.standardDrinks,
                derivedCaloriesKcal = p.servingMacros?.caloriesKcal,
            )
        }
    }

    /** Save (create or update). Only persists once ABV% + volume are present. */
    fun save() {
        val editor = _state.value.editor ?: return
        val abv = editor.abvPercent.toDoubleOrNull()
        val volume = editor.servingVolumeMl.toDoubleOrNull()
        if (editor.name.isBlank() || abv == null || volume == null) {
            updateEditor { it.copy(error = "Name, ABV% and serving volume are required") }
            return
        }
        val mixer = mixerMacros(editor)
        val label = editor.servingLabel.trim().ifBlank { null }
        updateEditor { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val op = runCatching {
                if (editor.drinkId == null) {
                    repo.createDrink(editor.name.trim(), abv, volume, label, mixer)
                } else {
                    repo.updateDrink(editor.drinkId, editor.name.trim(), abv, volume, label, mixer)
                }
            }
            op.fold(
                onSuccess = {
                    _state.update { it.copy(editor = null) }
                    refresh()
                },
                onFailure = { e ->
                    updateEditor { it.copy(saving = false, error = e.message ?: "Failed to save drink") }
                },
            )
        }
    }

    /** Build the per-serving mixer macros from the form, or null when both are blank. */
    private fun mixerMacros(editor: EditorState): Macros? {
        val carbs = editor.carbsGrams.toDoubleOrNull()
        val sugar = editor.sugarGrams.toDoubleOrNull()
        return if (carbs == null && sugar == null) null
        else Macros(carbsGrams = carbs, sugarGrams = sugar)
    }

    fun regenerateImage(drink: Food) {
        _state.update { it.copy(message = "Regenerating image…") }
        viewModelScope.launch {
            runCatching { repo.regenerateImage(drink.foodId) }.fold(
                onSuccess = { refresh() },
                onFailure = { e ->
                    _state.update { it.copy(message = e.message ?: "Couldn't regenerate image") }
                },
            )
        }
    }

    /**
     * Move [drink] one place toward the top of the list (a no-op if already first),
     * persisting the new order. Optimistically reorders the on-screen list so it
     * moves instantly, then PUTs the full ordered id list; a failure refreshes back
     * to the server truth.
     */
    fun moveUp(drink: Food) {
        val drinks = _state.value.drinks
        val index = drinks.indexOfFirst { it.foodId == drink.foodId }
        if (index <= 0) return
        reorderTo(drinks.swapped(index, index - 1))
    }

    /**
     * Move [drink] one place toward the bottom of the list (a no-op if already last),
     * persisting the new order. Optimistic, like [moveUp].
     */
    fun moveDown(drink: Food) {
        val drinks = _state.value.drinks
        val index = drinks.indexOfFirst { it.foodId == drink.foodId }
        if (index < 0 || index >= drinks.lastIndex) return
        reorderTo(drinks.swapped(index, index + 1))
    }

    /** Optimistically apply [reordered], persist it, and revert (refresh) on failure. */
    private fun reorderTo(reordered: List<Food>) {
        _state.update { it.copy(drinks = reordered) }
        val orderedIds = reordered.map { it.foodId }
        viewModelScope.launch {
            runCatching { repo.reorder(orderedIds) }.onFailure { e ->
                _state.update { it.copy(message = e.message ?: "Couldn't save order") }
                refresh()
            }
        }
    }

    fun archive(drink: Food) {
        viewModelScope.launch {
            runCatching { repo.archiveDrink(drink.foodId) }.fold(
                onSuccess = {
                    _state.update { s -> s.copy(drinks = s.drinks.filterNot { it.foodId == drink.foodId }) }
                },
                onFailure = { e ->
                    _state.update { it.copy(message = e.message ?: "Couldn't archive drink") }
                },
            )
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 4_000L
    }
}

/** Format a Double without a trailing ".0" so form fields read cleanly. */
internal fun trimNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/**
 * Return a copy of this list with the elements at [a] and [b] swapped. Pure +
 * bounds-safe (returns the list unchanged if either index is out of range) so the
 * move-up/move-down reorder is unit-testable without the ViewModel/Hilt.
 */
internal fun <T> List<T>.swapped(a: Int, b: Int): List<T> {
    if (a == b || a !in indices || b !in indices) return this
    return toMutableList().also { it[a] = this[b]; it[b] = this[a] }
}
