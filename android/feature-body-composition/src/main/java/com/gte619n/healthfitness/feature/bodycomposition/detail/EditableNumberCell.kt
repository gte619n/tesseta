package com.gte619n.healthfitness.feature.bodycomposition.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.input.EditableNumber

/**
 * Thin wrapper over core-ui's presentation-only [EditableNumber] that wires
 * the PATCH save semantics for the DEXA detail grid.
 *
 * For non-weight fields (percent, ratios, scores), pass [unit] directly and the
 * value flows through unchanged. For weight fields, the underlying value is
 * always stored in pounds: pass [weightUnit] instead and the cell converts the
 * displayed value to the user's chosen unit, labels it accordingly, and
 * converts any edit back to pounds before saving.
 */
@Composable
fun EditableNumberCell(
    value: Double?,
    onSave: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    fractionDigits: Int = 1,
    unit: String? = null,
    weightUnit: WeightUnit? = null,
    enabled: Boolean = true,
) {
    val displayValue = if (weightUnit != null && value != null) {
        UnitFormat.weightValue(value, weightUnit)
    } else {
        value
    }
    val suffix = if (weightUnit != null) UnitFormat.weightLabel(weightUnit) else unit
    val commit: (Double?) -> Unit = if (weightUnit == null) {
        onSave
    } else {
        { entered -> onSave(entered?.let { displayToLb(it, weightUnit) }) }
    }
    EditableNumber(
        value = displayValue,
        onCommit = commit,
        modifier = modifier,
        suffix = suffix,
        decimals = fractionDigits,
        enabled = enabled,
        placeholder = "—",
    )
}

/** Inverse of [UnitFormat.weightValue]: converts a display-unit value back to pounds. */
private fun displayToLb(displayValue: Double, unit: WeightUnit): Double = when (unit) {
    WeightUnit.POUNDS -> displayValue
    WeightUnit.KILOGRAMS -> displayValue * UnitFormat.LB_PER_KG
}
