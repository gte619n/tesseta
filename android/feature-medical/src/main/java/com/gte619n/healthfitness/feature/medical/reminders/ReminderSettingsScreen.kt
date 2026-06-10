package com.gte619n.healthfitness.feature.medical.reminders

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val WINDOW_LABELS = mapOf(
    TimeWindow.MORNING to "Morning",
    TimeWindow.AFTERNOON to "Afternoon",
    TimeWindow.EVENING to "Evening",
    TimeWindow.BEDTIME to "Bedtime",
)

/**
 * Medication-reminder settings (IMPL-16 Part A): master switch, the default
 * fire time per window, and per-medication overrides (mute / custom slot
 * times). Also surfaces the two system permissions reminders depend on —
 * notifications (runtime, Android 13+) and exact alarms (special access).
 */
@Composable
fun ReminderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ReminderSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickingWindow by remember { mutableStateOf<TimeWindow?>(null) }
    var pickingMedSlot by remember { mutableStateOf<Pair<String, TimeWindow>?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Dose reminders",
            subtitle = "When each medication notifies you",
            onBack = onBack,
        )

        when {
            state.loading -> LoadingState(label = "Loading reminder settings…")
            state.error != null -> ErrorState(message = state.error!!, onRetry = viewModel::load)
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NotificationPermissionBanner()
                ExactAlarmBanner()

                // Master switch
                HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Medication reminders",
                            style = Hf.type.headingSm,
                            color = Hf.colors.textPrimary,
                        )
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                }

                // Window default times
                Text("DEFAULT TIMES", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        TimeWindow.entries.forEach { window ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = state.enabled) { pickingWindow = window }
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    WINDOW_LABELS.getValue(window),
                                    style = Hf.type.bodyMd,
                                    color = Hf.colors.textPrimary,
                                )
                                Text(
                                    formatTime(state.windowTimes[window]),
                                    style = Hf.type.monoSm,
                                    color = if (state.enabled) Hf.colors.accentDim else Hf.colors.textTertiary,
                                )
                            }
                        }
                    }
                }

                // Per-medication overrides
                if (state.medications.isNotEmpty()) {
                    Text("PER MEDICATION", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                }
                state.medications.forEach { med ->
                    HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    med.displayName,
                                    style = Hf.type.headingSm,
                                    color = Hf.colors.textPrimary,
                                )
                                Switch(
                                    checked = state.isMedEnabled(med.medicationId),
                                    onCheckedChange = { viewModel.setMedEnabled(med.medicationId, it) },
                                    enabled = state.enabled,
                                )
                            }
                            val slots = med.timeSlots.map { it.window }
                                .ifEmpty { listOf(TimeWindow.MORNING) }
                                .distinct()
                            slots.forEach { window ->
                                val custom = state.isCustom(med.medicationId, window)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = state.enabled &&
                                                state.isMedEnabled(med.medicationId),
                                        ) { pickingMedSlot = med.medicationId to window }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        WINDOW_LABELS.getValue(window) +
                                            if (custom) " · custom" else "",
                                        style = Hf.type.bodySm,
                                        color = Hf.colors.textSecondary,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            formatTime(state.resolvedTime(med.medicationId, window)),
                                            style = Hf.type.monoSm,
                                            color = if (custom) Hf.colors.accentDim else Hf.colors.textTertiary,
                                        )
                                        if (custom) {
                                            Text(
                                                "  ✕",
                                                style = Hf.type.bodySm,
                                                color = Hf.colors.textTertiary,
                                                modifier = Modifier.clickable {
                                                    viewModel.setMedTime(med.medicationId, window, null)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Save
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Hf.colors.accent, RoundedCornerShape(8.dp))
                        .clickable(enabled = !state.saving) { viewModel.save(onSaved = onBack) }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            color = Hf.colors.textInverse,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Save reminders", style = Hf.type.capsSm, color = Hf.colors.textInverse)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    pickingWindow?.let { window ->
        TimePickerSheet(
            initial = state.windowTimes[window],
            onDismiss = { pickingWindow = null },
            onPicked = { time ->
                viewModel.setWindowTime(window, time)
                pickingWindow = null
            },
        )
    }
    pickingMedSlot?.let { (medId, window) ->
        TimePickerSheet(
            initial = state.resolvedTime(medId, window),
            onDismiss = { pickingMedSlot = null },
            onPicked = { time ->
                viewModel.setMedTime(medId, window, time)
                pickingMedSlot = null
            },
        )
    }
}

/** Android 13+ runtime notification permission, requested in place. */
@Composable
private fun NotificationPermissionBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    if (granted) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    PermissionBanner(
        text = "Allow notifications so dose reminders can appear.",
        actionLabel = "Allow",
        onAction = { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
    )
}

/** Android 12+ exact-alarm special access, opened in system settings. */
@Composable
private fun ExactAlarmBanner() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    var canExact by remember { mutableStateOf(alarmManager.canScheduleExactAlarms()) }
    if (canExact) return

    PermissionBanner(
        text = "Allow exact alarms so reminders fire at the precise time " +
            "(otherwise they may arrive a few minutes late).",
        actionLabel = "Open settings",
        onAction = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            canExact = alarmManager.canScheduleExactAlarms()
        },
    )
}

@Composable
private fun PermissionBanner(text: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
            modifier = Modifier.weight(1f).padding(end = 10.dp),
        )
        Text(
            actionLabel,
            style = Hf.type.capsSm,
            color = Hf.colors.accentDim,
            modifier = Modifier.clickable { onAction() },
        )
    }
}

/** Material3 time picker in a dialog; returns "HH:mm". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
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
                                    java.util.Locale.US,
                                    "%02d:%02d",
                                    pickerState.hour,
                                    pickerState.minute,
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

private fun formatTime(raw: String?): String =
    raw?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
        ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        ?: "—"
