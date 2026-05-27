package com.gte619n.healthfitness.feature.medical.add

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.feature.medical.components.DrugLookupProgress
import com.gte619n.healthfitness.feature.medical.components.FrequencySelector
import com.gte619n.healthfitness.feature.medical.components.TimeSlotEditor
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Full-screen modal: Add medication.
 *
 * SEARCH step — autocomplete against the local catalog, SSE for new
 * drugs; show "Add manually" when the lookup returns not_found.
 *
 * FORM / CUSTOM step — dose, unit, frequency, time slots, prescribed by,
 * notes. CUSTOM also takes a customName since there's no drug to back it.
 */
@Composable
fun AddMedicationScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: AddMedicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                text = when (state.step) {
                    AddMedicationStep.Search -> "Add medication"
                    AddMedicationStep.Form -> state.selectedDrug?.name ?: "Add medication"
                    AddMedicationStep.Custom -> "Add custom medication"
                },
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
        }

        when (state.step) {
            AddMedicationStep.Search -> SearchStep(
                state = state,
                onQueryChange = viewModel::onQueryChange,
                onSelectDrug = viewModel::selectDrug,
                onChooseManual = viewModel::chooseManualEntry,
            )
            AddMedicationStep.Form,
            AddMedicationStep.Custom -> FormStep(
                state = state,
                viewModel = viewModel,
                onSubmit = { viewModel.submit { onDone() } },
            )
        }
    }
}

@Composable
private fun SearchStep(
    state: AddMedicationUiState,
    onQueryChange: (String) -> Unit,
    onSelectDrug: (Drug) -> Unit,
    onChooseManual: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search drug catalog") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DrugLookupProgress(event = state.lookupEvent)

        if (state.lookupEvent is DrugLookupEvent.NotFound) {
            Spacer(Modifier.height(12.dp))
            NotFoundRow(
                message = state.lookupEvent.message ?: "No match found",
                onAddManually = onChooseManual,
            )
        }
        if (state.lookupEvent is DrugLookupEvent.Failed) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.lookupEvent.error,
                style = Hf.type.bodySm,
                color = Hf.colors.alert,
            )
        }

        Spacer(Modifier.height(12.dp))
        val matches = state.filtered.ifEmpty { state.catalog }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            matches.take(30).forEach { drug ->
                DrugListRow(drug, onClick = { onSelectDrug(drug) })
            }
        }
    }
}

@Composable
private fun DrugListRow(drug: Drug, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(drug.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(
                text = "${drug.category.name.lowercase()} · ${drug.form.name.lowercase().replace('_', ' ')}",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun NotFoundRow(message: String, onAddManually: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.canvasMuted)
            .padding(14.dp),
    ) {
        Text(message, style = Hf.type.bodySm, color = Hf.colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onAddManually) {
            Text("Add manually")
        }
    }
}

@Composable
private fun FormStep(
    state: AddMedicationUiState,
    viewModel: AddMedicationViewModel,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.step == AddMedicationStep.Custom) {
            OutlinedTextField(
                value = state.customName,
                onValueChange = viewModel::onCustomNameChange,
                label = { Text("Medication name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (state.dose == 0.0) "" else state.dose.toString(),
                onValueChange = {
                    viewModel.onDoseChange(it.toDoubleOrNull() ?: 0.0)
                },
                label = { Text("Dose") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.unit,
                onValueChange = viewModel::onUnitChange,
                label = { Text("Unit") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        FrequencySelector(
            config = state.frequency,
            onChange = viewModel::onFrequencyChange,
        )
        TimeSlotEditor(
            timeSlots = state.timeSlots,
            defaultDose = state.dose,
            onChange = viewModel::onTimeSlotsChange,
        )
        OutlinedTextField(
            value = state.prescribedBy,
            onValueChange = viewModel::onPrescribedByChange,
            label = { Text("Prescribed by (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = viewModel::onNotesChange,
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.error != null) {
            Text(state.error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSubmit,
            enabled = !state.isSubmitting && state.dose > 0.0 &&
                (state.step == AddMedicationStep.Form || state.customName.isNotBlank()),
            colors = ButtonDefaults.buttonColors(
                containerColor = Hf.colors.accent,
                contentColor = Hf.colors.textInverse,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    color = Hf.colors.textInverse,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Saving...")
            } else {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Save medication")
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
