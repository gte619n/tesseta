package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.EntryRequest
import com.gte619n.healthfitness.shared.data.Food
import com.gte619n.healthfitness.shared.data.FoodRepository
import com.gte619n.healthfitness.shared.data.LabelCaptureFood
import com.gte619n.healthfitness.shared.data.MealCaptureItem
import com.gte619n.healthfitness.shared.data.NutritionCaptureRepository
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import com.gte619n.healthfitness.shared.data.NutritionOpQueue
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import com.gte619n.healthfitness.shared.domain.nutrition.forPortion
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave C — shared camera-capture state machine. Port of
 * Android `feature-nutrition/NutritionCaptureViewModel`. D11: on iOS the *inputs*
 * to this VM come from AVFoundation + Vision (VNDetectBarcodesRequest →
 * [onBarcodeDetected]; VNRecognizeTextRequest OCR → [analyzeLabel]) instead of
 * CameraX + ML Kit — but the resolve/log/durable-op logic is shared and identical.
 *
 * Barcode hit → log immediately (default serving, qty 1) + [CaptureEvent.NavigateBack].
 * Barcode miss → offer the label-photo fallback. Meal photo + leftover photo →
 * durable op (survives process death), pop back before the upload even starts.
 * Label OCR → editable draft → durable confirm.
 *
 * `currentMeal` is supplied by the platform (no clock in commonMain); the SwiftUI
 * layer passes `Meal.forHour(hour)`.
 */
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
    data class MealItems(val items: List<MealCaptureItem>) : CaptureStage

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
    data object NavigateBack : CaptureEvent
}

class NutritionCaptureViewModel(
    private val foods: FoodRepository,
    private val capture: NutritionCaptureRepository,
    private val nutrition: NutritionDayRepository,
    private val ops: NutritionOpQueue,
    private val captureDate: String,
    private val currentMeal: Meal,
    /** IMPL-LEFTOVER-01 (D10): when set, the screen is scoped to this entry —
     *  skip barcode/label, go straight to the shutter, route bytes to the
     *  leftover op instead of new-meal logging. */
    val leftoverEntryId: String? = null,
) : ViewModel() {

    val isLeftoverMode: Boolean = leftoverEntryId != null

    private val _state = MutableStateFlow(NutritionCaptureUiState())
    val state: StateFlow<NutritionCaptureUiState> = _state.asStateFlow()

    private val _events = Channel<CaptureEvent>(Channel.BUFFERED)
    val events: Flow<CaptureEvent> = _events.receiveAsFlow()

    fun reset() = _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }

    // ---- Barcode (fed by VNDetectBarcodesRequest) ------------------------

    /**
     * Called by the Vision barcode analyzer when a GTIN is detected. On a catalog
     * hit we log the food immediately (default serving, qty 1) and pop back — no
     * manual serving confirmation. On a miss we offer the label-photo fallback.
     */
    fun onBarcodeDetected(code: String) {
        if (_state.value.stage != CaptureStage.Scanning) return
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val food = foods.barcodeLookup(code)
                if (food != null) logBarcodeFoodImmediately(food)
                else _state.update { it.copy(stage = CaptureStage.BarcodeMiss(code)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Lookup failed") }
            }
        }
    }

    private suspend fun logBarcodeFoodImmediately(food: Food) {
        val index = food.defaultServingIndex.coerceIn(0, (food.servingSizes.size - 1).coerceAtLeast(0))
        val serving = food.servingSizes.getOrNull(index)
        val servingGrams = serving?.grams ?: 100.0
        val servingLabel = serving?.label ?: "100 g"
        val macros = food.macrosPer100g.forPortion(servingGrams, 1.0)
        try {
            nutrition.addEntry(
                captureDate,
                EntryRequest(
                    meal = currentMeal.wire,
                    foodId = food.foodId,
                    foodName = food.name,
                    servingLabel = servingLabel,
                    servingGrams = servingGrams,
                    quantity = 1.0,
                    macros = macros,
                    source = "BARCODE",
                ),
            )
            _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }
            _events.send(CaptureEvent.NavigateBack)
        } catch (e: Exception) {
            _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
        }
    }

    /** From a BarcodeMiss, return to scanning; the OCR analyzer auto-detects the label. */
    fun fallbackToLabel() = _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }

    fun confirmBarcodeFood(food: Food, servingIndex: Int, quantity: Double) {
        val serving = food.servingSizes.getOrNull(servingIndex) ?: return
        val macros = food.macrosPer100g.forPortion(serving.grams, quantity)
        logEntry(
            EntryRequest(
                meal = currentMeal.wire,
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

    // ---- Photo: meal (durable op) ----------------------------------------

    /**
     * Capture a meal photo and pop straight back — before the upload starts. The
     * JPEG rides a durable CAPTURE_PHOTO op (survives process death); the Today
     * page shows a synthetic "Analyzing photo…" row until the real entry lands.
     */
    fun analyzeMeal(jpeg: ByteArray) {
        _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }
        viewModelScope.launch {
            ops.enqueueCapturePhoto(captureDate, currentMeal.wire, jpeg)
            _events.send(CaptureEvent.NavigateBack)
        }
    }

    /** IMPL-LEFTOVER-01 (D10): capture the leftover-plate photo → durable
     *  REMOVE_LEFTOVERS op → the target entry shows "Analyzing leftovers…". */
    fun analyzeLeftover(jpeg: ByteArray) {
        val entryId = leftoverEntryId ?: return
        _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }
        viewModelScope.launch {
            nutrition.analyzeLeftovers(captureDate, entryId, jpeg)
            _events.send(CaptureEvent.NavigateBack)
        }
    }

    /** Confirm the edited itemized meal → durable CONFIRM_MEAL_ITEMS op. */
    fun confirmMealItems(items: List<MealCaptureItem>) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            ops.enqueueConfirmMealItems(captureDate, currentMeal.wire, items)
            _state.update { it.copy(stage = CaptureStage.Done("${items.size} item(s) logged.")) }
        }
    }

    // ---- Photo: label (fed by VNRecognizeTextRequest OCR) ----------------

    /**
     * Analyze a nutrition-label photo. The OCR text (from VNRecognizeTextRequest)
     * gates the capture on the Swift side via [looksLikeNutritionLabel]; the JPEG
     * is sent to the AI label endpoint which returns the editable packaged-food
     * draft.
     */
    fun analyzeLabel(jpeg: ByteArray, barcode: String? = null) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val food = capture.analyzeLabel(jpeg, barcode)
                _state.update { it.copy(stage = CaptureStage.LabelDraft(food)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Analysis failed") }
            }
        }
    }

    /** Confirm the label draft → durable CONFIRM_LABEL op (create food + log entry). */
    fun confirmLabelDraft(draft: LabelCaptureFood, servingIndex: Int, quantity: Double) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            ops.enqueueConfirmLabel(captureDate, currentMeal.wire, draft, servingIndex, quantity)
            _state.update { it.copy(stage = CaptureStage.Done("${draft.name} logged.")) }
        }
    }

    // ---- shared ----------------------------------------------------------

    private fun logEntry(body: EntryRequest, doneMessage: String) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                nutrition.addEntry(captureDate, body)
                _state.update { it.copy(stage = CaptureStage.Done(doneMessage)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Save failed") }
            }
        }
    }
}
