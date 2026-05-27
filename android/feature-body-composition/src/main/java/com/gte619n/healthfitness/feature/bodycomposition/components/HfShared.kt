package com.gte619n.healthfitness.feature.bodycomposition.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Local copies of the surface atoms used by the dashboard / feature-blood
 * package. Until these primitives migrate to core-ui (cross-module visual
 * dedup is its own line item), the body-composition feature inlines them
 * to keep the styling identical without depending on `:app` or
 * `:feature-blood`.
 */
@Composable
fun HfCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp)),
    ) {
        content()
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = Hf.type.capsMd.copy(fontSize = 10.sp),
        color = Hf.colors.textTertiary,
        modifier = modifier,
    )
}
