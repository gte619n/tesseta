package com.gte619n.healthfitness.shared.presentation.nutrition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.Leftover
import com.gte619n.healthfitness.shared.data.NutritionDayRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave C — shared Remove-Leftovers review-flow VM
 * (IMPL-LEFTOVER-01). Backs the LeftoverReviewView sheet (parity: Android
 * `LeftoverReviewSheet`).
 *
 * The photo upload itself rides the capture VM's durable REMOVE_LEFTOVERS op; this
 * VM owns the review sheet: on a [Leftover] whose `status == PENDING_REVIEW` it
 * shows the Served→Ate diff and [apply]/[discard]s the server-held proposal, and
 * [restore]s the full served portion on an APPLIED entry (spec D15). States are
 * expressed through [PendingNutritionOp] so the notification deep links
 * (leftover-review / retake) drive the same lifecycle.
 */
data class LeftoverUiState(
    val entryId: String,
    val foodName: String,
    val leftover: Leftover? = null,
    val saving: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
) {
    val op: PendingNutritionOp get() = leftover.pendingOp()
    val proposal get() = leftover?.proposal
    val isApplied: Boolean get() = op == PendingNutritionOp.APPLIED
}

class LeftoverViewModel(
    private val repository: NutritionDayRepository,
    private val date: String,
    entryId: String,
    foodName: String,
    leftover: Leftover? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LeftoverUiState(entryId = entryId, foodName = foodName, leftover = leftover),
    )
    val state: StateFlow<LeftoverUiState> = _state.asStateFlow()

    fun apply() {
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX) || _state.value.proposal == null) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.applyLeftovers(date, id)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't apply leftovers") }
            }
        }
    }

    fun discard() {
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.discardLeftovers(date, id)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't discard leftovers") }
            }
        }
    }

    /** Reset live = served on an APPLIED entry (D15). */
    fun restoreFullPortion() {
        val id = _state.value.entryId
        if (id.startsWith(PENDING_CAPTURE_PREFIX)) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.restoreLeftovers(date, id)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Couldn't restore the portion") }
            }
        }
    }
}
