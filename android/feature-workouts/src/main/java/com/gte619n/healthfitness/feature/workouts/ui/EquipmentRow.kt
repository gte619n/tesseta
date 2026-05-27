package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * One row in a gym's equipment list. Tap to open the override sheet;
 * trash icon to remove from the gym. The "Modified" badge appears when
 * the gym has a per-location override for this equipment.
 */
@Composable
fun EquipmentRow(
    equipment: Equipment,
    hasOverride: Boolean,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Hf.colors.canvasMuted),
        ) {
            if (equipment.imageUrl != null) {
                HfAsyncImage(
                    model = equipment.imageUrl,
                    contentDescription = equipment.name,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )
            } else {
                Icon(
                    Icons.Outlined.FitnessCenter,
                    contentDescription = null,
                    tint = Hf.colors.textQuaternary,
                    modifier = Modifier.size(24.dp).align(Alignment.Center),
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    equipment.name,
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (hasOverride) ModifiedBadge()
            }
            Spacer(Modifier.size(2.dp))
            Text(
                "${equipment.category} / ${equipment.subcategory}",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Remove from gym",
                tint = Hf.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun ModifiedBadge() {
    Box(
        modifier = Modifier
            .background(Hf.colors.warnBg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "Modified",
            style = Hf.type.bodySm,
            color = Hf.colors.warnDim,
        )
    }
}
