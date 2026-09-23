package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.MealAdjustment
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave C — shared "Adjust with AI" review-flow VM. Backs the
 * MealAdjustReviewView sheet (parity: Android `AdjustReviewSheet` + the submit
 * affordance in `AdjustWithAiSection`).
 *
 * Two entry points:
 *  - [submit] a free-text correction (fire-and-forget durable op; a notification
 *    lands when the proposal is ready).
 *  - open the review sheet on a [MealAdjustment] whose `status == PENDING_REVIEW`
 *    and [apply]/[discard] the server-held proposal, with the "also save" toggle
 *    overriding the submit-time choice.
 *
 * The lifecycle is expressed through [PendingNutritionOp] so the deep-linked
 * notification (adjust-review) and the in-app banner drive the same states.
 */
data class MealAdjustUiState(
    val entryId: String,
    val foodName: String,
    val adjustment: MealAdjustment? = null,
    /** Pre-filled from the submit-time choice; the review sheet can change it. */
    val saveAsMeal: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
) {
    val op: PendingNutritionOp get() = adjustment.pendingOp()
    val proposal get() = adjustment?.proposal

    /** A single product proposal (packaged) has one item; a composite is a meal. */
    val isSingleProduct: Boolean get() = proposal?.packagedProduct == true && proposal?.items?.size == 1

    /** Offer "also save to source" for a composite meal, or a single product with a
     *  catalog foodId (updated in place server-side). Mirrors Android's gate. */
    fun canSaveToSource(entryHasFoodId: Boolean): Boolean =
        proposal != null && (!isSingleProduct || entryHasFoodId)
}

class MealAdjustViewModel(
    private val repository: NutritionDayRepository,
    private val date: String,
    entryId: String,
    foodName: String,
    adjustment: MealAdjustment? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MealAdjustUiState(
            entryId = entryId,
            foodName = foodName,
            adjustment = adjustment,
            saveAsMeal = adjustment?.saveAsMeal ?: false,
        ),
    )
    val state: StateFlow<MealAdjustUiState> = _state.asStateFlow()

    fun setSaveAsMeal(value: Boolean) = _state.update { it.copy(saveAsMeal = value) }

    /** Submit a free-text correction as a durable op, then mark done (sheet closes). */
    fun submit(instruction: String, saveAsMeal: Boolean) {
        val text = instruction.trim()
        if (text.isBlank()) return
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX)) return
        viewModelScope.launch {
            runCatching { repository.submitAdjust(date, id, text, saveAsMeal) }
            _state.update { it.copy(done = true) }
        }
    }

    /** Apply the stored proposal (with the possibly-overridden saveAsMeal). */
    fun apply() {
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX) || _state.value.proposal == null) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.commitAdjust(date, id, _state.value.saveAsMeal)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't adjust the meal") }
            }
        }
    }

    fun discard() {
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.discardAdjust(date, id)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't discard the adjustment") }
            }
        }
    }
}
