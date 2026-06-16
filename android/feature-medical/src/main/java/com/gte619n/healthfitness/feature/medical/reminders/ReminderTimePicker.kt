package com.gte619n.healthfitness.feature.medical.reminders

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Shared Material3 time picker dialog returning "HH:mm". Reused by the dedicated
 * reminder-settings screen and the inline reminder controls in the add/edit
 * medication flow (IMPL-STAB Workstream F item 5) so neither duplicates the
 * picker chrome.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderTimePickerDialog(
    initial: String?,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val initialTime = initial?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ?: LocalTime.of(8, 0)
    val pickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = false,
    )
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Hf.colors.canvas, RoundedCornerShape(16.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TimePicker(state = pickerState)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
                        .clickable { onDismiss() }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", style = Hf.type.capsSm, color = Hf.colors.textSecondary)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Hf.colors.accent, RoundedCornerShape(8.dp))
                        .clickable {
                            onPicked(
                                String.format(
                                    Locale.US, "%02d:%02d", pickerState.hour, pickerState.minute,
                                ),
                            )
                        }
                        .padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Set time", style = Hf.type.capsSm, color = Hf.colors.textInverse)
                }
            }
        }
    }
}

/** Format an "HH:mm" string for display (localized short time), or "—" if absent. */
fun formatReminderTime(raw: String?): String =
    raw?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        ?: "—"
