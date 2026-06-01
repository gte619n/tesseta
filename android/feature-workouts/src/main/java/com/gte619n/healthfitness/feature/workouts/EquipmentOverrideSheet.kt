package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentSpecForm
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Modal bottom sheet that edits per-location equipment spec overrides. Loads
 * the catalog equipment's schema + default specs (overlaid with any existing
 * override) and dispatches to [EquipmentSpecForm] by schema.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentOverrideSheet(
    locationId: String,
    equipmentId: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    vm: EquipmentOverrideViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(locationId, equipmentId) {
        vm.load(locationId, equipmentId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Hf.colors.accent, strokeWidth = 2.dp)
                }
                state.equipment == null -> Text(
                    state.error ?: "Failed to load equipment",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.alert,
                )
                else -> {
                    val equipment = state.equipment!!
                    Text("Override: ${equipment.name}", style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                    EquipmentSpecForm(
                        schema = equipment.specSchema,
                        specs = state.specs,
                        onChange = vm::update,
                    )
                    state.error?.let { Text(it, style = Hf.type.bodySm, color = Hf.colors.alert) }
                    Button(
                        onClick = { vm.save(locationId, equipmentId) { onSaved() } },
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.submitting) "Saving…" else "Save override")
                    }
                }
            }
        }
    }
}
