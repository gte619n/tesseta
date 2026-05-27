package com.gte619n.healthfitness.feature.workouts.addequipment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentSpecForm
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEquipmentSheet(
    locationId: String,
    onDismiss: () -> Unit,
    vm: AddEquipmentViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val tab by vm.activeTab.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val submit by vm.submit.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Add equipment",
                style = Hf.type.headingLg,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            TabSwitcher(
                tab = tab,
                onSelect = vm::selectTab,
            )
            Spacer(Modifier.height(12.dp))
            when (tab) {
                AddEquipmentViewModel.Tab.CATALOG -> CatalogPane(
                    state = catalog,
                    onQueryChange = vm::setQuery,
                    onAdd = { equipmentId ->
                        vm.addFromCatalog(
                            locationId = locationId,
                            equipmentId = equipmentId,
                            onDone = onDismiss,
                        )
                    },
                )
                AddEquipmentViewModel.Tab.SUBMIT -> SubmitPane(
                    state = submit,
                    onChange = vm::updateSubmitForm,
                    onSubmit = {
                        vm.submitNew(locationId = locationId, onDone = onDismiss)
                    },
                )
            }
        }
    }
}

@Composable
private fun TabSwitcher(
    tab: AddEquipmentViewModel.Tab,
    onSelect: (AddEquipmentViewModel.Tab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.canvasMuted)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(3.dp),
    ) {
        TabChip(
            label = "Catalog",
            active = tab == AddEquipmentViewModel.Tab.CATALOG,
            onClick = { onSelect(AddEquipmentViewModel.Tab.CATALOG) },
            modifier = Modifier.weight(1f),
        )
        TabChip(
            label = "Submit new",
            active = tab == AddEquipmentViewModel.Tab.SUBMIT,
            onClick = { onSelect(AddEquipmentViewModel.Tab.SUBMIT) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TabChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Hf.colors.surface else Hf.colors.canvasMuted)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Hf.type.bodyMd,
            color = if (active) Hf.colors.textPrimary else Hf.colors.textTertiary,
        )
    }
}

@Composable
private fun CatalogPane(
    state: AddEquipmentViewModel.CatalogState,
    onQueryChange: (String) -> Unit,
    onAdd: (equipmentId: String) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search equipment", style = Hf.type.bodyMd) },
        textStyle = Hf.type.bodyMd,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        when {
            state.loading -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Hf.colors.accent, strokeWidth = 2.dp)
            }
            state.error != null -> Text(
                state.error,
                style = Hf.type.bodySm,
                color = Hf.colors.alert,
            )
            state.results.isEmpty() -> Text(
                "No equipment found",
                style = Hf.type.bodyMd,
                color = Hf.colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.results, key = { it.equipmentId }) { eq ->
                    CatalogRow(eq, onAdd = { onAdd(eq.equipmentId) })
                }
            }
        }
    }
}

@Composable
private fun CatalogRow(equipment: Equipment, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Hf.colors.canvasMuted),
        ) {
            if (equipment.imageUrl != null) {
                HfAsyncImage(
                    model = equipment.imageUrl,
                    contentDescription = equipment.name,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Icon(
                    Icons.Outlined.FitnessCenter,
                    contentDescription = null,
                    tint = Hf.colors.textQuaternary,
                    modifier = Modifier.size(20.dp).align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(equipment.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(
                "${equipment.category} / ${equipment.subcategory}",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(
                containerColor = Hf.colors.accent,
                contentColor = Hf.colors.surface,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) { Text("Add", style = Hf.type.bodySm) }
    }
}

@Composable
private fun SubmitPane(
    state: AddEquipmentViewModel.SubmitState,
    onChange: ((AddEquipmentViewModel.SubmitState) -> AddEquipmentViewModel.SubmitState) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = state.name,
        onValueChange = { v -> onChange { it.copy(name = v) } },
        label = { Text("Equipment name", style = Hf.type.bodySm) },
        textStyle = Hf.type.bodyMd,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.category,
            onValueChange = { v -> onChange { it.copy(category = v) } },
            label = { Text("Category", style = Hf.type.bodySm) },
            textStyle = Hf.type.bodyMd,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = state.subcategory,
            onValueChange = { v -> onChange { it.copy(subcategory = v) } },
            label = { Text("Subcategory", style = Hf.type.bodySm) },
            textStyle = Hf.type.bodyMd,
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    SchemaPicker(
        selected = state.schema,
        onSelect = { schema ->
            onChange {
                it.copy(
                    schema = schema,
                    specs = defaultSpecsFor(schema),
                )
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    EquipmentSpecForm(
        schema = state.schema,
        current = state.specs,
        onChange = { spec -> onChange { it.copy(specs = spec) } },
    )
    if (state.error != null) {
        Spacer(Modifier.height(8.dp))
        Text(state.error, style = Hf.type.bodySm, color = Hf.colors.alert)
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onSubmit,
        enabled = !state.submitting,
        colors = ButtonDefaults.buttonColors(
            containerColor = Hf.colors.accent,
            contentColor = Hf.colors.surface,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (state.submitting) "Submitting..." else "Submit and add",
            style = Hf.type.bodyMd,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SchemaPicker(
    selected: SpecSchemaTag,
    onSelect: (SpecSchemaTag) -> Unit,
) {
    Column {
        Text(
            "Spec schema",
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SpecSchemaTag.entries.forEach { tag ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (tag == selected) Hf.colors.accentBg else Hf.colors.surface)
                        .border(
                            0.5.dp,
                            if (tag == selected) Hf.colors.accent else Hf.colors.borderDefault,
                            RoundedCornerShape(16.dp),
                        )
                        .clickable { onSelect(tag) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        tag.label(),
                        style = Hf.type.bodySm,
                        color = if (tag == selected) Hf.colors.accentDim else Hf.colors.textSecondary,
                    )
                }
            }
        }
    }
}

private fun SpecSchemaTag.label(): String = when (this) {
    SpecSchemaTag.SELECTORIZED -> "Selectorized"
    SpecSchemaTag.PLATE_LOADED -> "Plate-loaded"
    SpecSchemaTag.BODYWEIGHT -> "Bodyweight"
    SpecSchemaTag.CABLE -> "Cable"
    SpecSchemaTag.CARDIO -> "Cardio"
    SpecSchemaTag.WEIGHT_SET -> "Weight set"
}

private fun defaultSpecsFor(schema: SpecSchemaTag): EquipmentSpec = when (schema) {
    SpecSchemaTag.SELECTORIZED -> EquipmentSpec.Selectorized(0.0, 0.0, 0.0)
    SpecSchemaTag.PLATE_LOADED -> EquipmentSpec.PlateLoaded(0.0, emptyList())
    SpecSchemaTag.BODYWEIGHT -> EquipmentSpec.Bodyweight
    SpecSchemaTag.CABLE -> EquipmentSpec.Cable(0.0, 1)
    SpecSchemaTag.CARDIO -> EquipmentSpec.Cardio(0, false)
    SpecSchemaTag.WEIGHT_SET -> EquipmentSpec.WeightSet(null, null, null, null)
}
