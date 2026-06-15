package com.gte619n.healthfitness.feature.medical.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

private val WINDOW_LABELS = mapOf(
    TimeWindow.MORNING to "Morning",
    TimeWindow.AFTERNOON to "Afternoon",
    TimeWindow.EVENING to "Evening",
    TimeWindow.BEDTIME to "Bedtime",
)

/**
 * Per-medication reminder configuration captured inline in the add/edit flow
 * (IMPL-STAB Workstream F item 5). Maps directly onto the existing
 * `MedicationReminderOverride` model — [enabled] is the per-med mute, [times]
 * holds only the windows the user pinned to a custom time (unset windows fall
 * back to the global default at plan time). Not duplicating the reminder data
 * model: this is just the editable slice the form mutates.
 */
data class InlineReminderConfig(
    val enabled: Boolean = true,
    val times: Map<TimeWindow, String> = emptyMap(),
)

/**
 * Inline reminder controls for the add/edit medication form: a per-med
 * enable/disable toggle and, for each [slotWindows] the medication doses at, an
 * optional "Remind at…" override (cleared back to the global default with ✕).
 * [globalWindowTimes] supplies the fallback time shown when a slot is unset.
 */
@Composable
fun InlineReminderControls(
    config: InlineReminderConfig,
    slotWindows: List<TimeWindow>,
    globalWindowTimes: Map<TimeWindow, String>,
    onChange: (InlineReminderConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<TimeWindow?>(null) }
    val windows = slotWindows.ifEmpty { listOf(TimeWindow.MORNING) }.distinct()

    Column(modifier = modifier.fillMaxWidth()) {
        CapsLabel("Reminders", color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Remind me to take this",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textPrimary,
                    )
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { onChange(config.copy(enabled = it)) },
                    )
                }
                if (config.enabled) {
                    windows.forEach { window ->
                        val custom = config.times.containsKey(window)
                        val resolved = config.times[window]
                            ?: globalWindowTimes[window]
                            ?: ReminderSettings.DEFAULT_WINDOW_TIMES[window]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { picking = window }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                WINDOW_LABELS.getValue(window) + if (custom) " · custom" else "",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textSecondary,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatReminderTime(resolved),
                                    style = Hf.type.monoSm,
                                    color = if (custom) Hf.colors.accentDim else Hf.colors.textTertiary,
                                )
                                if (custom) {
                                    Text(
                                        "  ✕",
                                        style = Hf.type.bodySm,
                                        color = Hf.colors.textTertiary,
                                        modifier = Modifier.clickable {
                                            onChange(config.copy(times = config.times - window))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { window ->
        ReminderTimePickerDialog(
            initial = config.times[window]
                ?: globalWindowTimes[window]
                ?: ReminderSettings.DEFAULT_WINDOW_TIMES[window],
            onDismiss = { picking = null },
            onPicked = { time ->
                onChange(config.copy(times = config.times + (window to time)))
                picking = null
            },
        )
    }
}
