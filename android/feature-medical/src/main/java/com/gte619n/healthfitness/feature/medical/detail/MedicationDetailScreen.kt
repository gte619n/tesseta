package com.gte619n.healthfitness.feature.medical.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.DiscontinueReasonLabels
import com.gte619n.healthfitness.domain.medications.DosagePeriod
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.FrequencyFormatter
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationHistoryEntry
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.feature.medical.components.AdherenceSparkline
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.feature.medical.components.DiscontinueDialog
import com.gte619n.healthfitness.feature.medical.components.FrequencySelector
import com.gte619n.healthfitness.feature.medical.components.DrugImage
import com.gte619n.healthfitness.feature.medical.components.categoryLabel
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

@Composable
fun MedicationDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: MedicationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()

    LaunchedEffect(deleted) {
        if (deleted) onDeleted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(title = "Medication", onBack = onBack)
        when (val s = state) {
            is MedicationDetailUiState.Loading -> LoadingState(label = "Loading…")
            is MedicationDetailUiState.Error -> ErrorState(message = s.message, onRetry = viewModel::refresh)
            is MedicationDetailUiState.Ready -> DetailContent(
                detail = s.detail,
                actionInFlight = s.actionInFlight,
                onChangeDose = viewModel::changeDose,
                onEditStartDate = viewModel::editStartDate,
                onEditSchedule = viewModel::updateSchedule,
                onDiscontinue = viewModel::discontinue,
                onReactivate = viewModel::reactivate,
                onDelete = viewModel::delete,
            )
        }
    }
}

@Composable
private fun DetailContent(
    detail: MedicationDetail,
    actionInFlight: Boolean,
    onChangeDose: (dose: Double, unit: String?, startDate: LocalDate?, notes: String?) -> Unit,
    onEditStartDate: (LocalDate) -> Unit,
    onEditSchedule: (FrequencyConfig) -> Unit,
    onDiscontinue: (DiscontinueReason, String?, LocalDate) -> Unit,
    onReactivate: (LocalDate?) -> Unit,
    onDelete: () -> Unit,
) {
    val med = detail.medication
    var showChangeDose by remember { mutableStateOf(false) }
    var showDiscontinue by remember { mutableStateOf(false) }
    var showResume by remember { mutableStateOf(false) }
    var showEditStart by remember { mutableStateOf(false) }
    var showEditSchedule by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        // Header: image + name + category.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DrugImage(drug = med.drug, modifier = Modifier.size(72.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(med.displayName, style = Hf.type.headingLg.copy(fontSize = 18.sp), color = Hf.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    med.drug?.category?.let { Pill(text = categoryLabel(it), tone = HfTone.Neutral) }
                    if (med.status == MedicationStatus.DISCONTINUED) {
                        Pill(text = "Discontinued", tone = HfTone.Alert)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        // Current dose + frequency + time slots.
        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                KeyValueRow("Dose", DoseFormatter.format(med.dose, med.unit))
                KeyValueRow("Frequency", FrequencyFormatter.format(med.frequency))
                if (med.timeSlots.isNotEmpty()) {
                    KeyValueRow("Times", med.timeSlots.joinToString(", ") { TimeWindowLabels.label(it.window) })
                }
                KeyValueRow("Start date", med.startDate.toString())
                med.endDate?.let { KeyValueRow("End date", it.toString()) }
                med.prescribedBy?.let { KeyValueRow("Prescribed by", it) }
                med.discontinueReason?.let {
                    KeyValueRow("Reason", DiscontinueReasonLabels.label(it))
                }
            }
        }

        // Dosing history timeline ([PR#8]) — render only when > 1 period.
        if (med.dosagePeriods.size > 1) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("Dosing history")
            Spacer(Modifier.height(10.dp))
            DosingTimeline(periods = med.dosagePeriods)
        }

        // Dose-change history list.
        if (detail.history.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("Changes")
            Spacer(Modifier.height(10.dp))
            detail.history.sortedByDescending { it.changedAt }.forEach { entry ->
                HistoryRow(entry)
                Spacer(Modifier.height(8.dp))
            }
        }

        // Adherence.
        Spacer(Modifier.height(20.dp))
        SectionTitle("Adherence (30 days)")
        Spacer(Modifier.height(10.dp))
        AdherenceSparkline(adherence = med.adherence, modifier = Modifier.fillMaxWidth())

        // Correlated markers.
        if (med.correlatedMarkers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            SectionTitle("Correlated markers")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                med.correlatedMarkers.forEach { Pill(text = it, tone = HfTone.Neutral) }
            }
        }

        // Actions.
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (med.status == MedicationStatus.ACTIVE) {
                ActionButton("Change dose", enabled = !actionInFlight) { showChangeDose = true }
                ActionButton("Edit schedule", enabled = !actionInFlight) { showEditSchedule = true }
                ActionButton("Edit start date", enabled = !actionInFlight) { showEditStart = true }
                ActionButton("Discontinue", enabled = !actionInFlight, tone = HfTone.Alert) { showDiscontinue = true }
            } else {
                ActionButton("Resume", enabled = !actionInFlight) { showResume = true }
            }
            ActionButton("Delete", enabled = !actionInFlight, tone = HfTone.Alert) { showDelete = true }
        }
        Spacer(Modifier.height(28.dp))
    }

    // ---- dialogs ----
    if (showChangeDose) {
        DateDoseDialog(
            title = "Change dose",
            currentUnit = med.unit,
            onConfirm = { dose, unit, date, notes ->
                showChangeDose = false
                onChangeDose(dose, unit, date, notes)
            },
            onDismiss = { showChangeDose = false },
        )
    }
    if (showEditStart) {
        DatePickerDialog(
            title = "Edit start date",
            initial = med.startDate,
            onConfirm = { date ->
                showEditStart = false
                onEditStartDate(date)
            },
            onDismiss = { showEditStart = false },
        )
    }
    if (showEditSchedule) {
        EditScheduleDialog(
            initial = med.frequency,
            onConfirm = { frequency ->
                showEditSchedule = false
                onEditSchedule(frequency)
            },
            onDismiss = { showEditSchedule = false },
        )
    }
    if (showDiscontinue) {
        DiscontinueDialog(
            onConfirm = { reason, notes, endDate ->
                showDiscontinue = false
                onDiscontinue(reason, notes, endDate)
            },
            onDismiss = { showDiscontinue = false },
        )
    }
    if (showResume) {
        DatePickerDialog(
            title = "Resume from",
            initial = LocalDate.now(),
            onConfirm = { date ->
                showResume = false
                onReactivate(date)
            },
            onDismiss = { showResume = false },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Delete medication?",
            message = "This permanently removes the medication and its history.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                showDelete = false
                onDelete()
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun DosingTimeline(periods: List<DosagePeriod>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        periods.sortedByDescending { it.startDate }.forEach { period ->
            HfCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            DoseFormatter.format(period.dose, period.unit),
                            style = Hf.type.headingSm,
                            color = Hf.colors.textPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            rangeLabel(period),
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    }
                    if (period.isActive) {
                        Pill(text = "Current", tone = HfTone.Good)
                    }
                }
            }
        }
    }
}

/**
 * Human-readable date range. End dates are exclusive on the wire ([PR#8]); we
 * subtract a day for display so a closed period reads up to its last active day.
 */
private fun rangeLabel(period: DosagePeriod): String {
    val start = period.startDate.toString()
    val end = period.endDate
    return if (end == null) {
        "$start – Present"
    } else {
        "$start – ${end.minusDays(1)}"
    }
}

@Composable
private fun HistoryRow(entry: MedicationHistoryEntry) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                entry.changeType.name.replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() },
                style = Hf.type.headingSm,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${entry.previousValue} → ${entry.newValue}",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            entry.notes?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
    }
}

@Composable
private fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CapsLabel(key, color = Hf.colors.textTertiary)
        Text(value, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
    }
}

@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean = true,
    tone: HfTone = HfTone.Neutral,
    onClick: () -> Unit,
) {
    val fg = if (tone == HfTone.Alert) Hf.colors.alert else Hf.colors.textSecondary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hf.type.capsSm, color = fg)
    }
}

/** Dialog collecting a new dose + unit + effective date + notes ([PR#8]). */
@Composable
private fun DateDoseDialog(
    title: String,
    currentUnit: String,
    onConfirm: (dose: Double, unit: String?, date: LocalDate?, notes: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var dose by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(currentUnit) }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }

    val parsedDose = dose.toDoubleOrNull()
    val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            Column {
                CapsLabel("New dose", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = dose, onValueChange = { dose = it }, placeholder = "e.g. 250")
                Spacer(Modifier.height(10.dp))
                CapsLabel("Unit", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = unit, onValueChange = { unit = it }, placeholder = "mg")
                Spacer(Modifier.height(10.dp))
                CapsLabel("Effective date", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = dateText, onValueChange = { dateText = it }, placeholder = "yyyy-MM-dd")
                Spacer(Modifier.height(10.dp))
                CapsLabel("Notes", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = notes, onValueChange = { notes = it }, placeholder = "Optional")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsedDose!!, unit.ifBlank { null }, parsedDate, notes.ifBlank { null }) },
                enabled = parsedDose != null && parsedDate != null,
            ) {
                Text("Save", color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Hf.colors.textSecondary) }
        },
    )
}

/** Simple ISO-date entry dialog. */
@Composable
private fun DatePickerDialog(
    title: String,
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var dateText by remember { mutableStateOf(initial.toString()) }
    val parsed = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            Column {
                CapsLabel("Date", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = dateText, onValueChange = { dateText = it }, placeholder = "yyyy-MM-dd")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed!!) }, enabled = parsed != null) {
                Text("Save", color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Hf.colors.textSecondary) }
        },
    )
}

@Composable
private fun EditScheduleDialog(
    initial: FrequencyConfig,
    onConfirm: (FrequencyConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var frequency by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit schedule", style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            // Reuses the add-flow selector; for weekly meds this exposes the
            // day-of-week chips so a dose can be pinned to e.g. Monday.
            FrequencySelector(config = frequency, onChange = { frequency = it })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(frequency) }) {
                Text("Save", color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Hf.colors.textSecondary) }
        },
    )
}

@Composable
private fun DialogField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Hf.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Hf.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
