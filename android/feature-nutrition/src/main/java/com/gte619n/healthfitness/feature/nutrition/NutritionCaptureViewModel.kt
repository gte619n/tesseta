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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Top-level capture mode: scan a barcode, or take a photo. */
enum class CaptureMode { BARCODE, PHOTO }

/** Within photo mode: a full meal, or a nutrition label. */
enum class PhotoKind { MEAL, LABEL }

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
    val mode: CaptureMode = CaptureMode.BARCODE,
    val photoKind: PhotoKind = PhotoKind.MEAL,
    val meal: Meal = Meal.LUNCH,
    val stage: CaptureStage = CaptureStage.Scanning,
    val error: String? = null,
)

@HiltViewModel
class NutritionCaptureViewModel @Inject constructor(
    private val foods: FoodRepository,
    private val capture: NutritionCaptureRepository,
    private val nutrition: NutritionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NutritionCaptureUiState())
    val state: StateFlow<NutritionCaptureUiState> = _state.asStateFlow()

    private val today: String get() = LocalDate.now().format(ISO_DATE)

    fun setMode(mode: CaptureMode) =
        _state.update { it.copy(mode = mode, stage = CaptureStage.Scanning, error = null) }

    fun setPhotoKind(kind: PhotoKind) = _state.update { it.copy(photoKind = kind) }

    fun setMeal(meal: Meal) = _state.update { it.copy(meal = meal) }

    fun reset() = _state.update { it.copy(stage = CaptureStage.Scanning, error = null) }

    // ---- Barcode --------------------------------------------------------

    /** Called by the ML Kit analyzer when a GTIN is detected. */
    fun onBarcodeDetected(code: String) {
        val s = _state.value
        if (s.mode != CaptureMode.BARCODE || s.stage != CaptureStage.Scanning) return
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val food = foods.barcodeLookup(code)
                _state.update {
                    it.copy(
                        stage = if (food != null) {
                            CaptureStage.BarcodeFood(code, food)
                        } else {
                            CaptureStage.BarcodeMiss(code)
                        },
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Lookup failed")
                }
            }
        }
    }

    /** From a BarcodeMiss, switch to the label-photo fallback. */
    fun fallbackToLabel() = _state.update {
        it.copy(mode = CaptureMode.PHOTO, photoKind = PhotoKind.LABEL, stage = CaptureStage.Scanning)
    }

    fun confirmBarcodeFood(food: Food, servingIndex: Int, quantity: Double) {
        val serving = food.servingSizes.getOrNull(servingIndex) ?: return
        val macros = food.macrosPer100g.forPortion(serving.grams, quantity)
        logEntry(
            EntryRequest(
                meal = _state.value.meal.wire,
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

    fun analyzeMeal(jpeg: ByteArray) {
        _state.update { it.copy(stage = CaptureStage.Working, error = null) }
        viewModelScope.launch {
            try {
                val proposal = capture.analyzeMeal(jpeg)
                _state.update { it.copy(stage = CaptureStage.MealItems(proposal.items, jpeg)) }
            } catch (e: Exception) {
                _state.update { it.copy(stage = CaptureStage.Scanning, error = e.message ?: "Analysis failed") }
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
                            meal = _state.value.meal.wire,
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
                        meal = _state.value.meal.wire,
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
