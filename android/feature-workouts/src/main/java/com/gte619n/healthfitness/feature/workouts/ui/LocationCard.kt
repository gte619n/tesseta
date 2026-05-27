package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * One row in the gyms list — cover photo (16:9), name with default
 * star, address, and an amenity icon strip (top 4).
 *
 * Used by both the phone list and the foldable grid.
 */
@Composable
fun LocationCard(
    location: Location,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.FitnessCenter,
                    contentDescription = null,
                    tint = Hf.colors.textQuaternary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = location.name,
                    style = Hf.type.headingMd,
                    color = Hf.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (location.isDefault) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Default gym",
                        tint = Hf.colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = location.address ?: "No address",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Hf.colors.borderSubtle),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${location.equipmentIds.size} equipment items",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            if (location.amenities.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                AmenityChipsRow(location.amenities.take(4))
            }
        }
    }
}

@Composable
private fun AmenityChipsRow(amenities: List<Amenity>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        amenities.forEach { amenity ->
            Box(
                modifier = Modifier
                    .background(Hf.colors.canvasMuted, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = amenity.label,
                    style = Hf.type.bodySm.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = Hf.colors.textSecondary,
                )
            }
        }
    }
}
