package com.gte619n.healthfitness.feature.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.nutrition.FoodRepository
import com.gte619n.healthfitness.data.nutrition.NutritionCaptureRepository
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.FoodCreateRequest
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.MealCaptureItem
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the screen is currently showing/confirming. */
sealed interface CaptureStage {
    /** Live camera; nothing resolved yet. */
    data object Scanning : CaptureStage

    /** Working: lookup or AI analysis in flight. */
    data object Working : CaptureStage

    /** A scanned barcode resolved to a catalog food; confirm serving + qty. */
    data class BarcodeFood(val code: String, val food: Food) : CaptureStage

    /** Barcode resolved to nothing (404). Offer the label-photo fallback. */
    data class BarcodeMiss(val code: String) : CaptureStage

    /** Editable itemized meal proposal. */
    data class MealItems(val items: List<MealCaptureItem>, val jpeg: ByteArray) : CaptureStage

    /** A label-photo packaged-food draft; confirm to create + log. */
    data class LabelDraft(val food: LabelCaptureFood) : CaptureStage

    /** A terminal success message (entry logged). */
    data class Done(val message: String) : CaptureStage
}

data class NutritionCaptureUiState(
    val stage: CaptureStage = CaptureStage.Scanning,
    val error: String? = null,
)

/** One-shot side effects the capture screen reacts to (navigation). */
sealed interface CaptureEvent {
    /** Pop back to the nutrition page (e.g. after a one-tap barcode log). */
    data object NavigateBack : CaptureEvent
}

@HiltViewModel
class NutritionCaptureViewModel @Inject constructor(
    private val foods: FoodRepository,
    private val capture: NutritionCaptureRepository,
    private val nutrition: NutritionRepository,
    private val snackbar: SnackbarController,
    connectivity: com.gte619n.healthfitness.data.net.Connectivity,
) : ViewModel() {

    // IMPL-AND-20 (Phase 6, D17): meal-photo analysis is an online-only AI flow.
    // The capture screen disables the shutter + shows "needs connection" offline.
    val isOnline: StateFlow<Boolean> = connectivity.isOnline

    private val _state = MutableStateFlow(NutritionCaptureUiState())
    val state: StateFlow<NutritionCaptureUiState> = _state.asStateFlow()

    // One-shot navigation events. A barcode hit logs immediately and asks the
    // screen to pop back to the nutrition page (the user never confirms a serving).
    private val _events = Channel<CaptureEvent>(Channel.BUFFERED)
    val events: Flow<CaptureEvent> = _events.receiveAsFlow()

    private val today: String get() = LocalDate.now().format(ISO_DATE)

    // The meal is inferred from the time of day at log time — the capture flow
    // never asks the user which meal it is (the web client doesn't either).
    private fun currentMeal(): Meal = Meal.forHour(java.time.LocalTime.now().hour)

    fun reset() = _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }

    // ---- Barcode --------------------------------------------------------

    /**
     * Called by the ML Kit analyzer when a GTIN is detected. On a catalog hit we
     * log the food immediately (default serving, qty 1) and pop back to the
     * nutrition page — no manual serving confirmation. On a miss we offer the
     * label-photo fallback.
     */
    fun onBarcodeDetected(code: String) {
        val s = _state.value
        if (s.stage != CaptureStage.Scanning) return
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val food = foods.barcodeLookup(code)
                if (food != null) {
                    logBarcodeFoodImmediately(food)
                } else {
                    _state.update { it.copy(stage = CaptureStage.BarcodeMiss(code)) }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Lookup failed")
                }
            }
        }
    }

    /**
     * Log a scanned food using its default serving (the real product serving when
     * Open Food Facts supplies one — so a protein shake logs its ~30 g, not the
     * per-100g ~9 g), then signal the screen to return to the nutrition page.
     */
    private suspend fun logBarcodeFoodImmediately(food: Food) {
        val index = food.defaultServingIndex
            .coerceIn(0, (food.servingSizes.size - 1).coerceAtLeast(0))
        val serving = food.servingSizes.getOrNull(index)
        val servingGrams = serving?.grams ?: 100.0
        val servingLabel = serving?.label ?: "100 g"
        val macros = food.macrosPer100g.forPortion(servingGrams, 1.0)
        try {
            nutrition.addEntry(
                today,
                EntryRequest(
                    meal = currentMeal().wire,
                    foodId = food.foodId,
                    foodName = food.name,
                    servingLabel = servingLabel,
                    servingGrams = servingGrams,
                    quantity = 1.0,
                    macros = macros,
                    source = "BARCODE",
                ),
            )
            snackbar.show("${food.name} logged")
            _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }
            _events.send(CaptureEvent.NavigateBack)
        } catch (e: Exception) {
            _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
        }
    }

    /**
     * From a BarcodeMiss, return to scanning. The unified analyzer auto-detects
     * the nutrition label once the user points the camera at it.
     */
    fun fallbackToLabel() = _state.update {
        it.copy(stage = CaptureStage.Scanning, error = null)
    }

    fun confirmBarcodeFood(food: Food, servingIndex: Int, quantity: Double) {
        val serving = food.servingSizes.getOrNull(servingIndex) ?: return
        val macros = food.macrosPer100g.forPortion(serving.grams, quantity)
        logEntry(
            EntryRequest(
                meal = currentMeal().wire,
                foodId = food.foodId,
                foodName = food.name,
                servingLabel = serving.label,
                servingGrams = serving.grams,
                quantity = quantity,
                macros = macros,
                source = "BARCODE",
            ),
            doneMessage = "${food.name} logged.",
        )
    }

    // ---- Photo: meal ----------------------------------------------------

    /**
     * Capture a meal/product photo and hand it to the backend to analyze and log
     * asynchronously, then pop straight back to the nutrition page. The entry
     * appears there immediately as "Analyzing photo…" and fills itself in (name,
     * macros, ingredients, image) as the background analysis completes — the day
     * view polls for it. The (slow) analysis no longer blocks this screen.
     */
    fun analyzeMeal(jpeg: ByteArray) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                capture.captureMeal(today, currentMeal().wire, jpeg)
                // The capture POST creates the ANALYZING entry server-side only.
                // Pull it into the local mirror now so it renders immediately on
                // the Today screen and the settle-poll engages; without this the
                // offline-first day() won't re-fetch a non-empty date and nothing
                // shows. Best-effort — the entry still syncs later if this fails.
                runCatching { nutrition.refreshDay(today) }
                snackbar.show("Analyzing your photo…")
                _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }
                _events.send(CaptureEvent.NavigateBack)
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Capture failed") }
            }
        }
    }

    /**
     * Confirm the edited meal items. For each item with no catalog match we
     * create a GEMINI_PHOTO catalog food first, then log an entry referencing
     * it; matched items log directly. All entries use source="PHOTO".
     */
    fun confirmMealItems(items: List<MealCaptureItem>) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                for (item in items) {
                    val foodId = item.matchedFoodId ?: foods.create(
                        FoodCreateRequest(
                            name = item.name,
                            macrosPer100g = item.macrosPer100g,
                            servingSizes = listOf(
                                com.gte619n.healthfitness.domain.nutrition.ServingSize(
                                    label = item.suggestedServingLabel,
                                    grams = item.estimatedPortionGrams,
                                ),
                            ),
                            defaultServingIndex = 0,
                        ),
                    ).foodId
                    nutrition.addEntry(
                        today,
                        EntryRequest(
                            meal = currentMeal().wire,
                            foodId = foodId,
                            foodName = item.name,
                            servingLabel = item.suggestedServingLabel,
                            servingGrams = item.estimatedPortionGrams,
                            quantity = 1.0,
                            macros = item.macrosForPortion,
                            source = "PHOTO",
                        ),
                    )
                }
                _state.update {
                    it.copy(stage = CaptureStage.Done("${items.size} item(s) logged."))
                }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
            }
        }
    }

    // ---- Photo: label ---------------------------------------------------

    fun analyzeLabel(jpeg: ByteArray, barcode: String? = null) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val proposal = capture.analyzeLabel(jpeg, barcode)
                _state.update { it.copy(stage = CaptureStage.LabelDraft(proposal.food)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Analysis failed") }
            }
        }
    }

    /** Confirm the label draft: create the catalog food, then log an entry. */
    fun confirmLabelDraft(draft: LabelCaptureFood, servingIndex: Int, quantity: Double) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val created = foods.create(
                    FoodCreateRequest(
                        name = draft.name,
                        brand = draft.brand,
                        barcode = draft.barcode,
                        macrosPer100g = draft.macrosPer100g,
                        servingSizes = draft.servingSizes,
                        defaultServingIndex = draft.defaultServingIndex,
                    ),
                )
                val serving = created.servingSizes.getOrNull(servingIndex)
                    ?: draft.servingSizes.getOrNull(servingIndex)
                val grams = serving?.grams ?: 100.0
                val label = serving?.label ?: "1 serving"
                val macros = created.macrosPer100g.forPortion(grams, quantity)
                nutrition.addEntry(
                    today,
                    EntryRequest(
                        meal = currentMeal().wire,
                        foodId = created.foodId,
                        foodName = created.name,
                        servingLabel = label,
                        servingGrams = grams,
                        quantity = quantity,
                        macros = macros,
                        source = "LABEL",
                    ),
                )
                _state.update { it.copy(stage = CaptureStage.Done("${created.name} logged.")) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
            }
        }
    }

    // ---- shared ---------------------------------------------------------

    private fun logEntry(body: EntryRequest, doneMessage: String) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                nutrition.addEntry(today, body)
                _state.update { it.copy(stage = CaptureStage.Done(doneMessage)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
            }
        }
    }
}
