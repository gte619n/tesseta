package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Thumbnail for a food's generated studio image, shared by the add-food sheet
 * and the logged-entry rows. Renders the image when READY, a spinner while the
 * image is PENDING (still generating), and a utensil placeholder for NONE /
 * FAILED / not-yet-created food (or a load error).
 */
@Composable
fun FoodThumbnail(
    imageUrl: String?,
    imageStatus: String,
    size: Dp = 44.dp,
) {
    val shape = RoundedCornerShape(6.dp)
    when {
        imageStatus == "READY" && imageUrl != null -> {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.size(size).clip(shape),
                contentScale = ContentScale.Crop,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Loading -> Box(
                        Modifier.size(size).clip(shape).background(Hf.colors.canvasSunken),
                    )
                    is AsyncImagePainter.State.Error -> FoodThumbnailFallback(size)
                    else -> SubcomposeAsyncImageContent()
                }
            }
        }
        imageStatus == "PENDING" -> Box(
            modifier = Modifier.size(size).clip(shape).background(Hf.colors.canvasSunken),
            contentAlignment = Alignment.Center,
        ) {
            // Image is generating — show a spinner so the row reads as "working".
            CircularProgressIndicator(
                color = Hf.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(size * 0.4f),
            )
        }
        else -> FoodThumbnailFallback(size)
    }
}

@Composable
private fun FoodThumbnailFallback(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(Hf.colors.canvasSunken),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Restaurant,
            contentDescription = null,
            tint = Hf.colors.textTertiary,
            modifier = Modifier.size(size * 0.45f),
        )
    }
}
