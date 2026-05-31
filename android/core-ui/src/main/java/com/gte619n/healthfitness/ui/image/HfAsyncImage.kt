package com.gte619n.healthfitness.ui.image

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Project image wrapper (IMPL-AND-00). Applies a muted placeholder background
 * and crossfade. Used for drug imagery, gym cover photos, equipment thumbnails.
 */
@Composable
fun HfAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Hf.colors.canvasMuted,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier.background(placeholderColor),
        contentScale = contentScale,
    )
}
