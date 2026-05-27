package com.gte619n.healthfitness.feature.workouts.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.feature.workouts.ui.AmenityChipGrid
import com.gte619n.healthfitness.feature.workouts.ui.EquipmentRow
import com.gte619n.healthfitness.feature.workouts.ui.HoursMatrix
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Read-only gym detail screen. Hero cover photo, name with default
 * star, address, hours, amenities, equipment list. Mutations
 * (set-default, delete) are surfaced inline; cover-photo upload and
 * form edits live on the EditGym screen.
 */
@Composable
fun GymDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddEquipment: () -> Unit,
    onOpenOverride: (equipmentId: String) -> Unit,
    onDeleted: () -> Unit,
    vm: GymDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            DetailHeader(
                title = state.location?.name,
                onBack = onBack,
                onEdit = onEdit,
                onConfirmDelete = { confirmDelete = true },
                onSetDefault = vm::setDefault,
                isDefault = state.location?.isDefault == true,
            )
        },
        containerColor = Hf.colors.canvas,
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            when {
                state.loading -> LoadingState(label = "Loading gym...")
                state.error != null -> ErrorState(message = state.error!!, onRetry = vm::refresh)
                state.location != null -> {
                    val location = state.location!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        HeroCover(location)
                        Spacer(Modifier.height(14.dp))
                        Section(title = "Hours") {
                            if (location.is24Hours) {
                                Text(
                                    "Open 24 hours",
                                    style = Hf.type.bodyMd,
                                    color = Hf.colors.textPrimary,
                                )
                            } else {
                                val hoursMap = location.hours
                                if (hoursMap.isNullOrEmpty()) {
                                    Text(
                                        "No hours set",
                                        style = Hf.type.bodySm,
                                        color = Hf.colors.textTertiary,
                                    )
                                } else {
                                    // HoursMatrix expects Map<DayOfWeek, HoursSlot?>;
                                    // Location.hours uses non-nullable values, so
                                    // widen at the call boundary.
                                    val widened: Map<com.gte619n.healthfitness.domain.workouts.DayOfWeek, com.gte619n.healthfitness.domain.workouts.HoursSlot?> =
                                        hoursMap
                                    HoursMatrix(
                                        hours = widened,
                                        onChange = {},
                                        readOnly = true,
                                    )
                                }
                            }
                        }
                        Section(title = "Amenities") {
                            if (location.amenities.isEmpty()) {
                                Text(
                                    "No amenities listed",
                                    style = Hf.type.bodySm,
                                    color = Hf.colors.textTertiary,
                                )
                            } else {
                                AmenityChipGrid(
                                    selected = location.amenities.toSet(),
                                    onToggle = {},
                                    readOnly = true,
                                )
                            }
                        }
                        Section(
                            title = "Equipment (${state.equipment.size})",
                            trailing = {
                                TextButton(onClick = onAddEquipment) {
                                    Icon(
                                        Icons.Outlined.Add,
                                        contentDescription = null,
                                        tint = Hf.colors.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        "Add",
                                        style = Hf.type.bodySm,
                                        color = Hf.colors.accent,
                                    )
                                }
                            },
                        ) {
                            if (state.equipment.isEmpty()) {
                                EmptyEquipment(onAdd = onAddEquipment)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.equipment.forEach { eq ->
                                        EquipmentRow(
                                            equipment = eq,
                                            hasOverride = location.equipmentSpecs.containsKey(eq.equipmentId),
                                            onTap = { onOpenOverride(eq.equipmentId) },
                                            onRemove = { vm.removeEquipment(eq.equipmentId) },
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete gym?", style = Hf.type.headingMd) },
            text = {
                Text(
                    "This soft-deletes the gym; workout history that references it stays intact.",
                    style = Hf.type.bodyMd,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(onDeleted = onDeleted)
                }) {
                    Text("Delete", color = Hf.colors.alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            containerColor = Hf.colors.surface,
        )
    }
}

@Composable
private fun HeroCover(location: Location) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Hf.colors.canvasMuted),
    ) {
        if (location.coverPhotoUrl != null) {
            HfAsyncImage(
                model = location.coverPhotoUrl,
                contentDescription = location.name,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.FitnessCenter,
                contentDescription = null,
                tint = Hf.colors.textQuaternary,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
            )
        }
        location.address?.takeIf { it.isNotBlank() }?.let { addr ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Hf.colors.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(addr, style = Hf.type.bodySm, color = Hf.colors.textPrimary)
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title.uppercase(),
                style = Hf.type.capsMd,
                color = Hf.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderSubtle),
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun EmptyEquipment(onAdd: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No equipment in this gym yet",
                style = Hf.type.bodyMd,
                color = Hf.colors.textSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Hf.colors.accent,
                    contentColor = Hf.colors.surface,
                ),
            ) { Text("Add equipment", style = Hf.type.bodyMd) }
        }
    }
}

@Composable
private fun DetailHeader(
    title: String?,
    isDefault: Boolean,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Hf.colors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Hf.colors.textPrimary,
                )
            }
            Text(
                title ?: "Gym",
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSetDefault) {
                Icon(
                    if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (isDefault) "Default gym" else "Set as default",
                    tint = if (isDefault) Hf.colors.accent else Hf.colors.textSecondary,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit gym", tint = Hf.colors.textSecondary)
            }
            IconButton(onClick = onConfirmDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete gym", tint = Hf.colors.alert)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderDefault),
        )
    }
}
