package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentRow
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentSpecForm
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Modal bottom sheet for attaching equipment: a "Catalog" tab (debounced
 * server search → tap to attach) and a "Submit new" tab (creates equipment
 * then atomically attaches it to the current gym).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEquipmentSheet(
    locationId: String,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    vm: AddEquipmentViewModel = hiltViewModel(),
) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val submitForm by vm.submitForm.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == AddEquipmentViewModel.Tab.CATALOG,
                    onClick = { vm.selectTab(AddEquipmentViewModel.Tab.CATALOG) },
                    text = { Text("Catalog") },
                )
                Tab(
                    selected = tab == AddEquipmentViewModel.Tab.SUBMIT,
                    onClick = { vm.selectTab(AddEquipmentViewModel.Tab.SUBMIT) },
                    text = { Text("Submit new") },
                )
            }

            when (tab) {
                AddEquipmentViewModel.Tab.CATALOG -> {
                    OutlinedTextField(
                        value = catalog.query,
                        onValueChange = vm::setQuery,
                        label = { Text("Search equipment") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (catalog.loading) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = Hf.colors.accent, strokeWidth = 2.dp)
                        }
                    }
                    catalog.error?.let { Text(it, style = Hf.type.bodySm, color = Hf.colors.alert) }
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(catalog.results, key = { it.equipmentId }) { equipment ->
                            EquipmentRow(
                                equipment = equipment,
                                hasOverride = false,
                                onTap = { vm.addFromCatalog(locationId, equipment.equipmentId) { onAdded() } },
                                onRemove = { vm.addFromCatalog(locationId, equipment.equipmentId) { onAdded() } },
                            )
                        }
                    }
                }

                AddEquipmentViewModel.Tab.SUBMIT -> {
                    OutlinedTextField(
                        value = submitForm.name,
                        onValueChange = { vm.updateSubmit(submitForm.copy(name = it)) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = submitForm.category,
                        onValueChange = { vm.updateSubmit(submitForm.copy(category = it)) },
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = submitForm.subcategory,
                        onValueChange = { vm.updateSubmit(submitForm.copy(subcategory = it)) },
                        label = { Text("Subcategory") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Spec schema", style = Hf.type.capsSm, color = Hf.colors.muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        // Compact schema picker.
                        SpecSchemaTag.entries.forEach { schema ->
                            val selected = schema == submitForm.schema
                            Text(
                                schema.name,
                                style = Hf.type.capsSm,
                                color = if (selected) Hf.colors.accentDim else Hf.colors.textTertiary,
                                modifier = Modifier
                                    .clickable { vm.updateSubmit(submitForm.copy(schema = schema, specs = emptyMap())) }
                                    .padding(4.dp),
                            )
                        }
                    }
                    EquipmentSpecForm(
                        schema = submitForm.schema,
                        specs = submitForm.specs,
                        onChange = { vm.updateSubmit(submitForm.copy(specs = it)) },
                    )
                    submitForm.error?.let { Text(it, style = Hf.type.bodySm, color = Hf.colors.alert) }
                    androidx.compose.material3.Button(
                        onClick = { vm.submitNew(locationId) { onAdded() } },
                        enabled = !submitForm.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (submitForm.submitting) "Submitting…" else "Submit & add")
                    }
                }
            }
        }
    }
}
