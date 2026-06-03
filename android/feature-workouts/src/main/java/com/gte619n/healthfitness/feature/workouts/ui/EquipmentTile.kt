package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.ImageStatus
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** Target tile width; the column count is derived from this so tiles stay the
 *  same size and a wider screen simply fits more of them per row. */
private val TILE_TARGET_WIDTH = 180.dp
private val TILE_SPACING = 12.dp

/**
 * Responsive equipment grid: two tiles per row on a phone, more per row on a
 * tablet at the same tile size. Built as a non-lazy chunked grid because the
 * gym detail screen is itself a `verticalScroll` column (a nested
 * `LazyVerticalGrid` can't measure inside an infinite-height parent).
 */
@Composable
fun EquipmentTileGrid(
    equipment: List<Equipment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = (maxWidth / TILE_TARGET_WIDTH).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
            equipment.chunked(columns).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_SPACING)) {
                    rowItems.forEach { eq ->
                        EquipmentTile(
                            equipment = eq,
                            onRemove = { onRemove(eq.equipmentId) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Keep the last (partial) row's tiles at full column width.
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A single equipment tile: a square photo (or a category placeholder) with the
 * equipment name beneath it. Long-press surfaces a "Remove" action — there is
 * no per-location spec override here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EquipmentTile(
    equipment: Equipment,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.combinedClickable(
            onClick = {},
            onLongClick = { menuOpen = true },
        ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Hf.colors.canvasMuted),
            contentAlignment = Alignment.Center,
        ) {
            val hasImage = equipment.imageUrl != null && equipment.imageStatus == ImageStatus.GENERATED
            if (hasImage) {
                HfAsyncImage(
                    model = equipment.imageUrl,
                    contentDescription = equipment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = painterResource(equipmentPlaceholderRes(equipment.category)),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    colorFilter = ColorFilter.tint(Hf.colors.textQuaternary),
                )
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = equipment.name,
            style = Hf.type.bodyMd,
            color = Hf.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
