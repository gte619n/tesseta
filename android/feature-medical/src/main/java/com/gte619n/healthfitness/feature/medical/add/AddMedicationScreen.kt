package com.gte619n.healthfitness.feature.medical.add

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.feature.medical.components.DrugImage
import com.gte619n.healthfitness.feature.medical.components.DrugLookupProgress
import com.gte619n.healthfitness.feature.medical.components.FrequencySelector
import com.gte619n.healthfitness.feature.medical.components.TimeSlotEditor
import com.gte619n.healthfitness.feature.medical.components.categoryLabel
import com.gte619n.healthfitness.feature.medical.reminders.InlineReminderConfig
import com.gte619n.healthfitness.feature.medical.reminders.InlineReminderControls
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.sync.OfflineNotice
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun AddMedicationScreen(
    onDone: (Medication) -> Unit,
    onBack: () -> Unit,
    viewModel: AddMedicationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val online by viewModel.isOnline.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        ModalTopBar(
            title = when (state.step) {
                AddMedicationUiState.Step.SEARCH -> "Add medication"
                AddMedicationUiState.Step.FORM -> state.selectedDrug?.name ?: "Add medication"
                AddMedicationUiState.Step.CUSTOM -> "Custom medication"
            },
            onClose = onBack,
        )

        when (state.step) {
            AddMedicationUiState.Step.SEARCH -> SearchStep(
                state = state,
                online = online,
                onQueryChange = viewModel::onQueryChange,
                onSelectDrug = viewModel::selectDrug,
                onManual = viewModel::startManualEntry,
            )
            AddMedicationUiState.Step.FORM -> DoseForm(
                drug = state.selectedDrug,
                customName = null,
                customCategory = null,
                customForm = null,
                isSubmitting = state.isSubmitting,
                error = state.error,
                globalWindowTimes = state.globalWindowTimes,
                onBack = viewModel::backToSearch,
                onSubmit = { req, reminder -> viewModel.submit(req, reminder, onDone) },
            )
            AddMedicationUiState.Step.CUSTOM -> CustomEntryForm(
                initialName = state.query,
                isSubmitting = state.isSubmitting,
                error = state.error,
                globalWindowTimes = state.globalWindowTimes,
                onBack = viewModel::backToSearch,
                onSubmit = { req, reminder -> viewModel.submit(req, reminder, onDone) },
            )
        }
    }
}

@Composable
private fun SearchStep(
    state: AddMedicationUiState,
    online: Boolean,
    onQueryChange: (String) -> Unit,
    onSelectDrug: (Drug) -> Unit,
    onManual: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp),
    ) {
        LabeledField(label = "Search") {
            HfTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = "e.g. Testosterone Cypionate",
            )
        }
        Spacer(Modifier.height(12.dp))

        // Local catalog matches.
        state.filteredCatalog.forEach { drug ->
            DrugResultRow(drug = drug, onClick = { onSelectDrug(drug) })
            Spacer(Modifier.height(8.dp))
        }

        // D17 (#41): the AI drug lookup is online-only. Offline, when there's no
        // local catalog match for a meaningful query, show the "needs connection"
        // affordance instead of the SSE lookup region. Manual entry stays available.
        if (!online && state.filteredCatalog.isEmpty() && state.query.trim().length >= 3) {
            OfflineNotice(
                message = "Searching for a new drug uses AI and needs an internet connection. " +
                    "You can still add a medication manually.",
            )
        }

        // SSE lookup state.
        when (val event = state.lookupEvent) {
            is DrugLookupEvent.Progress -> DrugLookupProgress(event)
            is DrugLookupEvent.Found -> {
                // The VM auto-advances to FORM on Found; show a brief confirmation
                // in case of a re-composition race.
                Text(
                    "Found ${event.drug.name}",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.good,
                )
            }
            is DrugLookupEvent.NotFound -> NotFoundPrompt(
                message = event.message ?: "No match found.",
                onManual = onManual,
            )
            is DrugLookupEvent.Failed -> NotFoundPrompt(
                message = event.error,
                onManual = onManual,
            )
            null -> Unit
        }

        Spacer(Modifier.height(16.dp))
        // Always-available manual entry.
        TextButtonRow(label = "Add manually instead", onClick = onManual)
    }
}

@Composable
private fun DoseForm(
    drug: Drug?,
    customName: String?,
    customCategory: DrugCategory?,
    customForm: DrugForm?,
    isSubmitting: Boolean,
    error: String?,
    globalWindowTimes: Map<TimeWindow, String>,
    onBack: () -> Unit,
    onSubmit: (CreateMedicationRequest, InlineReminderConfig) -> Unit,
) {
    var dose by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(drug?.defaultUnit ?: "mg") }
    var frequency by remember { mutableStateOf(FrequencyConfig(type = FrequencyType.DAILY, timesPerPeriod = 1)) }
    var slots by remember { mutableStateOf(emptyList<TimeSlot>()) }
    var prescribedBy by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf(InlineReminderConfig()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        if (drug != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DrugImage(drug = drug, modifier = Modifier.size(56.dp))
                Column {
                    Text(drug.name, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                    Text(categoryLabel(drug.category), style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        DoseAndUnitRow(
            dose = dose,
            unit = unit,
            onDoseChange = { dose = it },
            onUnitChange = { unit = it },
        )
        Spacer(Modifier.height(16.dp))

        FrequencySelector(config = frequency, onChange = { frequency = it })
        Spacer(Modifier.height(16.dp))

        TimeSlotEditor(
            slots = slots,
            defaultDose = dose.toDoubleOrNull() ?: 0.0,
            onChange = { slots = it },
        )
        Spacer(Modifier.height(16.dp))

        // IMPL-STAB Workstream F (item 5): inline reminder controls. PRN ("as
        // needed") meds never schedule a reminder, so the controls are hidden.
        if (frequency.type != FrequencyType.PRN) {
            InlineReminderControls(
                config = reminder,
                slotWindows = slots.map { it.window },
                globalWindowTimes = globalWindowTimes,
                onChange = { reminder = it },
            )
            Spacer(Modifier.height(16.dp))
        }

        LabeledField(label = "Prescribed by") {
            HfTextField(value = prescribedBy, onValueChange = { prescribedBy = it }, placeholder = "Optional")
        }
        Spacer(Modifier.height(12.dp))
        LabeledField(label = "Notes") {
            HfTextField(value = notes, onValueChange = { notes = it }, placeholder = "Optional")
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButtonRow(label = "Back", onClick = onBack)
            PrimaryButton(
                label = if (isSubmitting) "Saving…" else "Save",
                enabled = !isSubmitting && (dose.toDoubleOrNull() != null) && unit.isNotBlank(),
                onClick = {
                    onSubmit(
                        CreateMedicationRequest(
                            drugId = drug?.drugId,
                            customName = customName,
                            customCategory = customCategory,
                            customForm = customForm,
                            dose = dose.toDoubleOrNull() ?: 0.0,
                            unit = unit,
                            frequency = frequency,
                            timeSlots = slots,
                            prescribedBy = prescribedBy.ifBlank { null },
                            notes = notes.ifBlank { null },
                        ),
                        if (frequency.type == FrequencyType.PRN) InlineReminderConfig() else reminder,
                    )
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CustomEntryForm(
    initialName: String,
    isSubmitting: Boolean,
    error: String?,
    globalWindowTimes: Map<TimeWindow, String>,
    onBack: () -> Unit,
    onSubmit: (CreateMedicationRequest, InlineReminderConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(DrugCategory.SUPPLEMENT) }
    var form by remember { mutableStateOf(DrugForm.TABLET) }
    var dose by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mg") }
    var frequency by remember { mutableStateOf(FrequencyConfig(type = FrequencyType.DAILY, timesPerPeriod = 1)) }
    var slots by remember { mutableStateOf(emptyList<TimeSlot>()) }
    var prescribedBy by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf(InlineReminderConfig()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        LabeledField(label = "Name") {
            HfTextField(value = name, onValueChange = { name = it }, placeholder = "Medication name")
        }
        Spacer(Modifier.height(12.dp))

        CapsLabel("Category", color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        EnumChips(
            entries = DrugCategory.entries,
            selected = category,
            label = { categoryLabel(it) },
            onSelect = { category = it },
        )
        Spacer(Modifier.height(12.dp))

        CapsLabel("Form", color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))
        EnumChips(
            entries = DrugForm.entries,
            selected = form,
            label = { formLabel(it) },
            onSelect = { form = it },
        )
        Spacer(Modifier.height(16.dp))

        DoseAndUnitRow(
            dose = dose,
            unit = unit,
            onDoseChange = { dose = it },
            onUnitChange = { unit = it },
        )
        Spacer(Modifier.height(16.dp))

        FrequencySelector(config = frequency, onChange = { frequency = it })
        Spacer(Modifier.height(16.dp))

        TimeSlotEditor(
            slots = slots,
            defaultDose = dose.toDoubleOrNull() ?: 0.0,
            onChange = { slots = it },
        )
        Spacer(Modifier.height(16.dp))

        // IMPL-STAB Workstream F (item 5): inline reminder controls (PRN excluded).
        if (frequency.type != FrequencyType.PRN) {
            InlineReminderControls(
                config = reminder,
                slotWindows = slots.map { it.window },
                globalWindowTimes = globalWindowTimes,
                onChange = { reminder = it },
            )
            Spacer(Modifier.height(16.dp))
        }

        LabeledField(label = "Prescribed by") {
            HfTextField(value = prescribedBy, onValueChange = { prescribedBy = it }, placeholder = "Optional")
        }
        Spacer(Modifier.height(12.dp))
        LabeledField(label = "Notes") {
            HfTextField(value = notes, onValueChange = { notes = it }, placeholder = "Optional")
        }

        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButtonRow(label = "Back", onClick = onBack)
            PrimaryButton(
                label = if (isSubmitting) "Saving…" else "Save",
                enabled = !isSubmitting && name.isNotBlank() &&
                    (dose.toDoubleOrNull() != null) && unit.isNotBlank(),
                onClick = {
                    onSubmit(
                        CreateMedicationRequest(
                            drugId = null,
                            customName = name,
                            customCategory = category,
                            customForm = form,
                            dose = dose.toDoubleOrNull() ?: 0.0,
                            unit = unit,
                            frequency = frequency,
                            timeSlots = slots,
                            prescribedBy = prescribedBy.ifBlank { null },
                            notes = notes.ifBlank { null },
                        ),
                        if (frequency.type == FrequencyType.PRN) InlineReminderConfig() else reminder,
                    )
                },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
