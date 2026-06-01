package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Gym summary card: 16:9 cover photo (or placeholder), name + default star,
 * address, equipment count, and the first four amenity labels.
 */
@Composable
fun LocationCard(
    location: Location,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Hf.colors.surface,
    ) {
        Column {
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
                    Icon(
                        Icons.Filled.FitnessCenter,
                        contentDescription = null,
                        tint = Hf.colors.textQuaternary,
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        location.name,
                        style = Hf.type.headingSm,
                        color = Hf.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (location.isDefault) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Default gym",
                            tint = Hf.colors.accent,
                        )
                    }
                }
                if (!location.address.isNullOrBlank()) {
                    Text(location.address!!, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
                Text(
                    "${location.equipmentIds.size} equipment",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textSecondary,
                )
                if (location.amenities.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        location.amenities.take(4).forEach { amenity ->
                            Pill(amenity.label, HfTone.Neutral)
                        }
                    }
                }
            }
        }
    }
}
