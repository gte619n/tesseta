package com.gte619n.healthfitness.ui.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Click-to-edit numeric field mirroring web's inline-edit pattern (IMPL-AND-00).
 * Tapping switches the static value to a [BasicTextField]; commits on focus loss
 * or the Done action. A blank field commits `null`; an unparseable entry reverts.
 */
@Composable
fun EditableNumber(
    value: Double?,
    onCommit: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Int = 1,
    enabled: Boolean = true,
    placeholder: String = "—",
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(formatNumber(value, decimals)) }
    val focusManager = LocalFocusManager.current

    fun commit() {
        editing = false
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> onCommit(null)
            else -> {
                val parsed = trimmed.toDoubleOrNull()
                if (parsed != null) onCommit(parsed) else text = formatNumber(value, decimals)
            }
        }
    }

    if (editing && enabled) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = Hf.type.monoMd.copy(color = Hf.colors.textPrimary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                commit()
                focusManager.clearFocus()
            }),
            modifier = modifier
                .border(1.dp, Hf.colors.accent, RoundedCornerShape(6.dp))
                .background(Hf.colors.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
    } else {
        val display = buildString {
            append(if (value == null) placeholder else formatNumber(value, decimals))
            if (value != null && suffix != null) append(" $suffix")
        }
        Row(
            modifier = modifier
                .then(if (enabled) Modifier.clickable { editing = true } else Modifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            androidx.compose.material3.Text(
                text = display,
                style = Hf.type.monoMd,
                color = if (value == null) Hf.colors.textQuaternary else Hf.colors.textPrimary,
            )
        }
    }
}

private fun formatNumber(value: Double?, decimals: Int): String {
    if (value == null) return ""
    return if (value % 1.0 == 0.0 && decimals == 0) {
        value.toInt().toString()
    } else {
        "%.${decimals}f".format(value)
    }
}
