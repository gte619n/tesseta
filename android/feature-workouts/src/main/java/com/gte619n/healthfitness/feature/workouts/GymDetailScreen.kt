package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import com.gte619n.healthfitness.feature.workouts.ui.AmenityChipGrid
import com.gte619n.healthfitness.feature.workouts.ui.DeleteLocationButton
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentTileGrid
import com.gte619n.healthfitness.feature.workouts.ui.HoursMatrix
import com.gte619n.healthfitness.feature.workouts.ui.SetDefaultButton
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun GymDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onDeleted: () -> Unit,
    vm: GymDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAddEquipment by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).background(Hf.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = state.location?.name ?: "Gym",
                subtitle = "Gym details and equipment",
                onBack = onBack,
                trailing = {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit gym",
                        tint = Hf.colors.textSecondary,
                        modifier = Modifier.size(20.dp).clickable { onEdit(vm.locationId) },
                    )
                },
            )

            when {
                state.loading -> LoadingState(Modifier.fillMaxSize())
                state.error != null && state.location == null -> ErrorState(
                    message = state.error!!,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = vm::refresh,
                )
                else -> {
                    val location = state.location ?: return@Column
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .background(Hf.colors.canvasMuted),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (location.coverPhotoUrl != null) {
                                HfAsyncImage(
                                    model = location.coverPhotoUrl,
                                    contentDescription = location.name,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                                )
                            } else {
                                Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = Hf.colors.textQuaternary)
                            }
                        }

                        if (!location.address.isNullOrBlank()) {
                            Text(location.address!!, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SetDefaultButton(isDefault = location.isDefault, onClick = vm::setDefault)
                        }

                        if (location.is24Hours) {
                            SectionTitle("Hours")
                            Text("Open 24 hours", style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
                        } else if (location.hours != null) {
                            SectionTitle("Hours")
                            HoursMatrix(
                                state = location.hours!!,
                                onChange = {},
                                enabled = false,
                            )
                        }

                        if (location.amenities.isNotEmpty()) {
                            SectionTitle("Amenities")
                            AmenityChipGrid(selected = location.amenities.toSet(), onToggle = {}, enabled = false)
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SectionTitle("Equipment")
                            OutlinedButton(onClick = { showAddEquipment = true }) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Text("  Add equipment")
                            }
                        }

                        if (state.equipment.isEmpty()) {
                            Text("No equipment yet.", style = Hf.type.bodySm, color = Hf.colors.muted)
                        } else {
                            EquipmentTileGrid(
                                equipment = state.equipment,
                                onRemove = { vm.removeEquipment(it) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        DeleteLocationButton(onConfirm = { vm.delete(onDeleted) })
                    }
                }
            }
        }
    }

    if (showAddEquipment) {
        AddEquipmentSheet(
            locationId = vm.locationId,
            onDismiss = { showAddEquipment = false },
            onAdded = {
                showAddEquipment = false
                vm.refresh()
            },
        )
    }

}
