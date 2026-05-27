package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Per-time-window editor. The four window chips toggle on/off; toggling
 * one on adds a [TimeSlot] using [defaultDose] (the form's main dose
 * field). Per-slot dose editing happens in the row below each enabled
 * window.
 */
@Composable
fun TimeSlotEditor(
    timeSlots: List<TimeSlot>,
    defaultDose: Double,
    onChange: (List<TimeSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "TIME WINDOWS",
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TimeWindow.values().forEach { window ->
                val active = timeSlots.any { it.window == window }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) Hf.colors.accentBg else Hf.colors.surface)
                        .border(
                            0.5.dp,
                            if (active) Hf.colors.accent else Hf.colors.borderDefault,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable {
                            val mutated = timeSlots.toMutableList()
                            if (active) {
                                mutated.removeAll { it.window == window }
                            } else {
                                mutated.add(TimeSlot(window, defaultDose))
                            }
                            onChange(mutated)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = TimeWindowLabels.shortLabel(window),
                        style = Hf.type.capsSm,
                        color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
                    )
                }
            }
        }
        if (timeSlots.size > 1) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "PER-SLOT DOSE",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            timeSlots.forEach { slot ->
                PerSlotRow(
                    slot = slot,
                    onChange = { updated ->
                        onChange(timeSlots.map { if (it.window == slot.window) updated else it })
                    },
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun PerSlotRow(slot: TimeSlot, onChange: (TimeSlot) -> Unit) {
    var raw by remember(slot.window) { mutableStateOf(slot.dose.toString()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = TimeWindowLabels.label(slot.window),
            style = Hf.type.bodySm,
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = raw,
            onValueChange = {
                raw = it
                it.toDoubleOrNull()?.let { d -> onChange(slot.copy(dose = d)) }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
}
