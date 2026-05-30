package com.gte619n.healthfitness.feature.medical.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.DosagePeriod
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.FrequencyFormatter
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationHistoryEntry
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.feature.medical.components.AdherenceSparkline
import com.gte619n.healthfitness.feature.medical.components.DiscontinueDialog
import com.gte619n.healthfitness.feature.medical.components.DrugImage
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Medication detail. Renders the drug image, name, category, dose,
 * frequency, time slots, prescribed-by, notes, 30-day adherence, the
 * correlated-markers chip row, and the dose-change history.
 *
 * Action row: Edit (dose only — V1), Discontinue (modal with reason),
 * Delete (confirm).
 */
@Composable
fun MedicationDetailScreen(
    onBack: () -> Unit,
    viewModel: MedicationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscontinue by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showEdit by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = Hf.colors.textPrimary)
            }
            Text(
                text = (state as? MedicationDetailUiState.Ready)?.detail?.medication?.displayName ?: "Medication",
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
        }
        when (val s = state) {
            MedicationDetailUiState.Loading -> LoadingState()
            is MedicationDetailUiState.Error -> ErrorState(message = s.message, onRetry = viewModel::load)
            is MedicationDetailUiState.Ready -> Body(
                detail = s.detail,
                onDiscontinueClick = { showDiscontinue = true },
                onDeleteClick = { showDelete = true },
                onEditClick = { showEdit = true },
            )
        }
    }

    if (showDiscontinue) {
        DiscontinueDialog(
            onDismiss = { showDiscontinue = false },
            onConfirm = { reason, notes ->
                showDiscontinue = false
                viewModel.discontinue(reason, notes) { onBack() }
            },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete medication") },
            text = { Text("This permanently removes the medication and its history.") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }
    if (showEdit) {
        (state as? MedicationDetailUiState.Ready)?.detail?.let { detail ->
            EditDoseDialog(
                currentDose = detail.medication.dose,
                onDismiss = { showEdit = false },
                onConfirm = { newDose, notes ->
                    showEdit = false
                    viewModel.updateDose(newDose, notes)
                },
            )
        }
    }
}

@Composable
private fun Body(
    detail: MedicationDetail,
    onDiscontinueClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onEditClick: () -> Unit,
) {
    val med = detail.medication
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .aspectRatio(1f),
            ) {
                DrugImage(
                    imageUrl = med.drug?.imageUrl,
                    imageFallback = med.drug?.imageFallback,
                    form = med.drug?.form,
                    contentDescription = med.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(med.displayName, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                med.drug?.category?.let {
                    Text(
                        it.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                }
                Text(
                    text = DoseFormatter.format(med.dose, med.unit),
                    style = Hf.type.monoMd,
                    color = Hf.colors.textPrimary,
                )
                Text(
                    text = FrequencyFormatter.format(med.frequency),
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
                if (med.timeSlots.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = med.timeSlots.joinToString(" · ") { TimeWindowLabels.label(it.window) },
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
        }

        SectionLabel("ADHERENCE (30 DAYS)")
        AdherenceSparkline(adherence = med.adherence)

        if (med.correlatedMarkers.isNotEmpty()) {
            SectionLabel("CORRELATED MARKERS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                med.correlatedMarkers.forEach { marker ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Hf.colors.canvasMuted)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(marker, style = Hf.type.capsSm, color = Hf.colors.textSecondary)
                    }
                }
            }
        }

        val prescribed = med.prescribedBy
        if (prescribed != null) {
            SectionLabel("PRESCRIBED BY")
            Text(prescribed, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        }
        val notes = med.notes
        if (notes != null) {
            SectionLabel("NOTES")
            Text(notes, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        }

        if (med.dosagePeriods.size > 1) {
            SectionLabel("DOSING HISTORY")
            med.dosagePeriods.sortedByDescending { it.startDate }.forEach { DosagePeriodRow(it) }
        }

        if (detail.history.isNotEmpty()) {
            SectionLabel("HISTORY")
            detail.history.forEach { HistoryRow(it) }
        }

        if (med.status == MedicationStatus.ACTIVE) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit dose")
                }
                OutlinedButton(onClick = onDiscontinueClick, modifier = Modifier.weight(1f)) {
                    Text("Discontinue")
                }
            }
        }
        OutlinedButton(
            onClick = onDeleteClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete medication", color = Hf.colors.alert)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(label, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
}

@Composable
private fun DosagePeriodRow(period: DosagePeriod) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = DoseFormatter.format(period.dose, period.unit),
            style = Hf.type.monoMd,
            color = Hf.colors.textPrimary,
        )
        val range = if (period.endDate != null) {
            "${period.startDate} - ${period.endDate}"
        } else {
            "${period.startDate} - Present"
        }
        Text(range, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    }
}

@Composable
private fun HistoryRow(entry: MedicationHistoryEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${entry.previousValue} → ${entry.newValue}",
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
            )
            val notes = entry.notes
            if (notes != null) {
                Spacer(Modifier.height(2.dp))
                Text(notes, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
        val formatted = remember(entry.changedAt) {
            entry.changedAt
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("MMM dd yyyy"))
        }
        Text(formatted, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    }
}

@Composable
private fun EditDoseDialog(
    currentDose: Double,
    onDismiss: () -> Unit,
    onConfirm: (Double, String?) -> Unit,
) {
    var dose by remember { mutableStateOf(currentDose.toString()) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit dose") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = dose,
                    onValueChange = { dose = it },
                    label = { Text("Dose") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                dose.toDoubleOrNull()?.let { onConfirm(it, notes.ifBlank { null }) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
