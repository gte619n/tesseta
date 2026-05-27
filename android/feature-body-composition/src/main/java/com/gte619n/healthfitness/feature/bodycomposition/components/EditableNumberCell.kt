package com.gte619n.healthfitness.feature.bodycomposition.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gte619n.healthfitness.ui.input.EditableNumber

/**
 * Thin wrapper around `core-ui`'s [EditableNumber] that surfaces the
 * PATCH callback used by the DEXA region grid. The optimistic
 * UI / revert / snackbar logic lives one layer up in the ViewModel — see
 * [com.gte619n.healthfitness.feature.bodycomposition.detail.DexaScanDetailViewModel.patchField].
 *
 * The wrapper exists so the DEXA grid never reaches across the module
 * boundary into `EditableNumber` directly; a future change to add (e.g.)
 * a save-spinner overlay can land here without touching `core-ui`.
 */
@Composable
fun EditableNumberCell(
    value: Double?,
    onSave: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    suffix: String? = null,
    enabled: Boolean = true,
) {
    EditableNumber(
        value = value,
        onCommit = onSave,
        modifier = modifier,
        suffix = suffix,
        decimals = decimals,
        enabled = enabled,
    )
}
