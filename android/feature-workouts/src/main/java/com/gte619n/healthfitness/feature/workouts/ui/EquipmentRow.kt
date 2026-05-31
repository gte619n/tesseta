package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.ImageStatus
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * One catalog-equipment row attached to a gym. Shows a thumbnail (or
 * placeholder for PENDING/FAILED images), name, category, a "Modified" badge
 * when a per-location override exists, and an overflow menu to remove.
 */
@Composable
fun EquipmentRow(
    equipment: Equipment,
    hasOverride: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Hf.colors.canvasMuted),
            contentAlignment = Alignment.Center,
        ) {
            val hasImage = equipment.imageUrl != null && equipment.imageStatus == ImageStatus.GENERATED
            if (hasImage) {
                HfAsyncImage(
                    model = equipment.imageUrl,
                    contentDescription = equipment.name,
                    modifier = Modifier.size(48.dp),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = painterResource(equipmentPlaceholderRes(equipment.category)),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(Hf.colors.textQuaternary),
                )
            }
        }

        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(equipment.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                if (hasOverride) {
                    Box(Modifier.padding(start = 8.dp)) { Pill("Modified", HfTone.Neutral) }
                }
            }
            Text(
                listOf(equipment.category, equipment.subcategory).filter { it.isNotBlank() }.joinToString(" · "),
                style = Hf.type.capsSm,
                color = Hf.colors.muted,
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Equipment options", tint = Hf.colors.muted)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Override specs") },
                    onClick = { menuOpen = false; onTap() },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}
