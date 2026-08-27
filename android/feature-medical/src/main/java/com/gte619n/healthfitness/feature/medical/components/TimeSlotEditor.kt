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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.feature.medical.reminders.ReminderTimePickerDialog
import com.gte619n.healthfitness.feature.medical.reminders.formatReminderTime
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.input.EditableNumber
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalTime

/**
 * Chip row of the four time windows; tapping a window toggles a [TimeSlot] for it
 * and reveals a per-slot dose input plus an optional per-slot reminder time.
 *
 * IMPL-21 (spec D16): the window is the preset — its default reminder time is shown
 * as a hint — and "Set time" reveals a picker that pins an explicit [TimeSlot.time]
 * for just that slot (the highest-precedence reminder time). Clearing reverts to the
 * window default. [windowDefaults] supplies the "HH:mm" default shown per window.
 */
@Composable
fun TimeSlotEditor(
    slots: List<TimeSlot>,
    defaultDose: Double,
    onChange: (List<TimeSlot>) -> Unit,
    modifier: Modifier = Modifier,
    windowDefaults: Map<TimeWindow, String> = ReminderSettings.DEFAULT_WINDOW_TIMES,
) {
    // Which selected slot's time picker is open (null = none).
    var editingTimeFor by remember { mutableStateOf<TimeWindow?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        CapsLabel("Time windows", color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimeWindow.entries.forEach { window ->
                val selected = slots.any { it.window == window }
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) Hf.colors.accentBg else Hf.colors.surface,
                            RoundedCornerShape(7.dp),
                        )
                        .border(
                            0.5.dp,
                            if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                            RoundedCornerShape(7.dp),
                        )
                        .clickable {
                            val next = if (selected) {
                                slots.filterNot { it.window == window }
                            } else {
                                slots + TimeSlot(window = window, dose = defaultDose)
                            }
                            onChange(next)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        TimeWindowLabels.label(window),
                        style = Hf.type.bodySm,
                        color = if (selected) Hf.colors.accentDim else Hf.colors.textSecondary,
                    )
                }
            }
        }

        val selectedSlots = slots.sortedBy { it.window.ordinal }
        if (selectedSlots.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            selectedSlots.forEach { slot ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        TimeWindowLabels.label(slot.window),
                        style = Hf.type.bodySm,
                        color = Hf.colors.textSecondary,
                        modifier = Modifier.width(84.dp),
                    )
                    EditableNumber(
                        value = slot.dose,
                        onCommit = { newDose ->
                            onChange(
                                slots.map {
                                    if (it.window == slot.window) it.copy(dose = newDose ?: 0.0) else it
                                },
                            )
                        },
                        modifier = Modifier.width(72.dp),
                        decimals = 2,
                        placeholder = "Dose",
                    )
                    Spacer(Modifier.width(10.dp))
                    // Per-slot reminder time (IMPL-21): explicit value or the window default.
                    val explicit = slot.time != null
                    val label = if (explicit) {
                        "⏰ ${formatReminderTime(slot.time.toString())}"
                    } else {
                        "Default ${formatReminderTime(windowDefaults[slot.window])}"
                    }
                    Box(
                        modifier = Modifier
                            .clickable { editingTimeFor = slot.window }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        Text(
                            label,
                            style = Hf.type.bodySm,
                            color = if (explicit) Hf.colors.accent else Hf.colors.textTertiary,
                        )
                    }
                    if (explicit) {
                        Spacer(Modifier.width(2.dp))
                        Box(
                            modifier = Modifier
                                .clickable {
                                    onChange(
                                        slots.map {
                                            if (it.window == slot.window) it.copy(time = null) else it
                                        },
                                    )
                                }
                                .padding(4.dp),
                        ) {
                            Text("✕", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                        }
                    }
                }
            }
        }
    }

    editingTimeFor?.let { window ->
        val current = slots.firstOrNull { it.window == window }?.time
            ?: windowDefaults[window]?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ReminderTimePickerDialog(
            initial = current?.toString(),
            onDismiss = { editingTimeFor = null },
            onPicked = { picked ->
                val time = runCatching { LocalTime.parse(picked) }.getOrNull()
                onChange(
                    slots.map { if (it.window == window) it.copy(time = time) else it },
                )
                editingTimeFor = null
            },
        )
    }
}
