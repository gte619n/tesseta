package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Renders a drug's image via the Coil wrapper. Prefers [Drug.imageUrl], falls
 * back to [Drug.imageFallback], and finally to a form-shaped placeholder icon
 * for custom (no-drug) entries or when no image is available.
 */
@Composable
fun DrugImage(
    drug: Drug?,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 10.dp,
) {
    val model = drug?.imageUrl ?: drug?.imageFallback
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Hf.colors.canvasMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            HfAsyncImage(
                model = model,
                contentDescription = drug?.name,
                modifier = Modifier.fillMaxSize(),
                placeholderColor = Hf.colors.canvasMuted,
            )
        } else {
            // Form-shaped fallback. We use a single generic medication glyph;
            // the form differentiates the tint to hint at the dosage form.
            Icon(
                imageVector = Icons.Outlined.Medication,
                contentDescription = drug?.name ?: "Medication",
                tint = formTint(drug?.form),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun formTint(form: DrugForm?) = when (form) {
    DrugForm.INJECTABLE_VIAL -> Hf.colors.accent
    DrugForm.CREAM, DrugForm.PATCH, DrugForm.LIQUID -> Hf.colors.good
    DrugForm.POWDER -> Hf.colors.warn
    else -> Hf.colors.textTertiary
}
