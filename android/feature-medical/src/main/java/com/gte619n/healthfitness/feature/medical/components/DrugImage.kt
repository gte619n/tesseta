package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Drug image with two-tier fallback:
 *  1. [imageUrl] from Coil; if it 404s or fails to load,
 *  2. [imageFallback] from Coil; if that fails too,
 *  3. a form-shaped pill icon on the canvas-muted background.
 *
 * While the backend is still generating the image (`imageUrl == null`),
 * we go straight to the fallback. The wrapping [Box] is always sized
 * 1:1; callers pick the size via the `size` modifier on the supplied
 * [modifier].
 */
@Composable
fun DrugImage(
    imageUrl: String?,
    imageFallback: String?,
    form: DrugForm?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.canvasMuted),
        contentAlignment = Alignment.Center,
    ) {
        val primary = imageUrl ?: imageFallback
        if (primary != null) {
            SubcomposeAsyncImage(
                model = primary,
                contentDescription = contentDescription,
                modifier = Modifier.size(0.dp).then(Modifier),
                loading = { FormPlaceholderIcon(form) },
                error = {
                    if (imageFallback != null && primary != imageFallback) {
                        SubcomposeAsyncImage(
                            model = imageFallback,
                            contentDescription = contentDescription,
                            loading = { FormPlaceholderIcon(form) },
                            error = { FormPlaceholderIcon(form) },
                        )
                    } else {
                        FormPlaceholderIcon(form)
                    }
                },
            )
        } else {
            FormPlaceholderIcon(form)
        }
    }
}

@Composable
private fun FormPlaceholderIcon(form: DrugForm?) {
    Icon(
        imageVector = Icons.Outlined.Medication,
        contentDescription = null,
        tint = Hf.colors.textQuaternary,
        modifier = Modifier.size(28.dp),
    )
}
