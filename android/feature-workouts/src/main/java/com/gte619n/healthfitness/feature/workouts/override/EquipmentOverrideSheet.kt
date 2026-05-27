package com.gte619n.healthfitness.feature.workouts.override

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentOverrideSheet(
    locationId: String,
    equipmentId: String,
    onDismiss: () -> Unit,
    vm: EquipmentOverrideViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(locationId, equipmentId) {
        vm.load(locationId, equipmentId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = Hf.colors.accent, strokeWidth = 2.dp)
                }
                state.equipment == null -> Text(
                    state.error ?: "Couldn't load equipment",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.alert,
                )
                state.unsupported -> Column {
                    Text(
                        "Spec schema not supported",
                        style = Hf.type.headingMd,
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open the web app to edit this equipment's specs.",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textSecondary,
                    )
                }
                else -> {
                    val eq = state.equipment!!
                    Text(
                        "Override specs",
                        style = Hf.type.headingLg,
                        color = Hf.colors.textPrimary,
                    )
                    Text(
                        eq.name,
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    EquipmentSpecForm(
                        schema = eq.specSchema,
                        current = state.spec,
                        onChange = vm::updateSpec,
                    )
                    if (state.error != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(state.error!!, style = Hf.type.bodySm, color = Hf.colors.alert)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            vm.save(
                                locationId = locationId,
                                equipmentId = equipmentId,
                                onDone = onDismiss,
                            )
                        },
                        enabled = !state.submitting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Hf.colors.accent,
                            contentColor = Hf.colors.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.submitting) "Saving..." else "Save override",
                            style = Hf.type.bodyMd,
                        )
                    }
                }
            }
        }
    }
}
