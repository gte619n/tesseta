package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Shared scaffolding for the entry edit sheets ([EditEntrySheet],
 * [IngredientsSheet]) so every block reads the same: one capsSm header style and
 * one spacing rhythm (18 dp above each section, 6 dp under its header, 8 dp
 * between the section's rows) instead of the per-callsite Spacer soup.
 */
@Composable
internal fun SheetSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * Compact action pill for a sheet hero's upper-right corner (e.g. the composite
 * sheet's Leftovers control). Outline by default; [accent] fills it for a
 * call-to-action state; [enabled] = false renders an inert, muted chip.
 */
@Composable
internal fun HeroPill(
    label: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    val decorated = when {
        accent && enabled -> Modifier.background(Hf.colors.accent, shape)
        else -> Modifier.border(0.5.dp, Hf.colors.borderStrong, shape)
    }
    Text(
        label,
        style = Hf.type.capsSm,
        color = when {
            !enabled -> Hf.colors.textTertiary
            accent -> Hf.colors.textInverse
            else -> Hf.colors.accent
        },
        modifier = modifier
            .clip(shape)
            .then(decorated)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
