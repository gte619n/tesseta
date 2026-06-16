package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.gte619n.healthfitness.ui.image.ImageZoomDialog
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
    zoomable: Boolean = true,
    // IMPL-STAB (Workstream E): when set, a FAILED image renders a tappable retry
    // chip instead of a silent utensil placeholder, so a missing picture is
    // recoverable rather than permanent.
    onRetry: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    when {
        imageStatus == "READY" && imageUrl != null -> {
            // Long-press a ready photo to open it full-screen for a closer look.
            var showZoom by remember(imageUrl) { mutableStateOf(false) }
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(shape)
                    .then(
                        if (zoomable) {
                            Modifier.pointerInput(imageUrl) {
                                detectTapGestures(onLongPress = { showZoom = true })
                            }
                        } else {
                            Modifier
                        },
                    ),
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
            if (showZoom) {
                ImageZoomDialog(model = imageUrl, onDismiss = { showZoom = false })
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
        imageStatus == "FAILED" && onRetry != null -> Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(Hf.colors.canvasSunken)
                .clickable { onRetry() },
            contentAlignment = Alignment.Center,
        ) {
            // Image generation failed — offer a one-tap retry rather than a silent
            // (permanent) placeholder.
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "Retry image",
                tint = Hf.colors.accent,
                modifier = Modifier.size(size * 0.45f),
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
