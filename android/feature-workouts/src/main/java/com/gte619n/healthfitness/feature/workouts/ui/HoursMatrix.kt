package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

private fun DayOfWeek.label(): String = when (this) {
    DayOfWeek.MON -> "Mon"
    DayOfWeek.TUE -> "Tue"
    DayOfWeek.WED -> "Wed"
    DayOfWeek.THU -> "Thu"
    DayOfWeek.FRI -> "Fri"
    DayOfWeek.SAT -> "Sat"
    DayOfWeek.SUN -> "Sun"
}

/**
 * Editable 7-row hours grid. Each row: day label, open/close 15-min
 * dropdowns, a "Closed" toggle (clears the row to null), and (rows 2-7) a
 * "copy from above" action. Read-only when [enabled] is false (used on the
 * detail screen).
 */
@Composable
fun HoursMatrix(
    state: Map<DayOfWeek, HoursSlot?>,
    onChange: (Map<DayOfWeek, HoursSlot?>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val days = DayOfWeek.entries
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEachIndexed { index, day ->
            val slot = state[day]
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(day.label(), style = Hf.type.bodyMd, color = Hf.colors.textPrimary, modifier = Modifier.width(40.dp))

                if (slot == null) {
                    Text("Closed", style = Hf.type.bodySm, color = Hf.colors.muted, modifier = Modifier.weight(1f))
                } else {
                    TimeDropdown(
                        value = slot.open,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onSelect = { newOpen ->
                            onChange(state + (day to slot.copy(open = newOpen)))
                        },
                    )
                    Text("–", style = Hf.type.bodyMd, color = Hf.colors.muted)
                    TimeDropdown(
                        value = slot.close,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onSelect = { newClose ->
                            onChange(state + (day to slot.copy(close = newClose)))
                        },
                    )
                }

                if (enabled) {
                    if (index > 0) {
                        IconButton(onClick = {
                            val prev = state[days[index - 1]]
                            onChange(state + (day to prev))
                        }) {
                            Icon(
                                Icons.Filled.ArrowUpward,
                                contentDescription = "Copy from day above",
                                tint = Hf.colors.muted,
                            )
                        }
                    }
                    // Closed toggle: on = open (defaults), off = closed (null).
                    Switch(
                        checked = slot != null,
                        onCheckedChange = { open ->
                            val next = if (open) (slot ?: HoursSlot("09:00", "17:00")) else null
                            onChange(state + (day to next))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeDropdown(
    value: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Text(
            text = TimeOptions.label(value),
            style = Hf.type.bodyMd,
            color = if (enabled) Hf.colors.textPrimary else Hf.colors.muted,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true }
                .padding(vertical = 8.dp),
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            val scroll = rememberScrollState()
            Column(Modifier.verticalScroll(scroll)) {
                TimeOptions.VALUES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(TimeOptions.label(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
