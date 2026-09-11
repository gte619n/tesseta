package com.gte619n.healthfitness.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// Shared building blocks for settings/form screens. Every settings card gets the
// same title/description typography and internal padding, and rows share one
// control vocabulary: SettingsNavRow to navigate, SettingsToggleRow for on/off,
// SegmentedChoice for small closed choices. See android/CLAUDE.md.

/**
 * Max width for settings/form content columns. Wider windows (tablet, unfolded)
 * center the column instead of stretching cards and buttons edge-to-edge.
 */
val SettingsContentMaxWidth = 600.dp

/**
 * A titled settings card: transparent [HfCard] with the canonical 14dp content
 * padding, `headingSm` title, and optional `bodySm` description.
 */
@Composable
fun SettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    HfCard(modifier = modifier, transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                if (description != null) {
                    Text(description, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
            }
            content()
        }
    }
}

/** Navigation row: label (+ optional subtitle) with a trailing chevron. */
@Composable
fun SettingsNavRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            if (subtitle != null) {
                Text(subtitle, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
        Text("›", style = Hf.type.bodyLg, color = Hf.colors.textTertiary)
    }
}

/**
 * On/off preference row with the app-standard switch treatment.
 * [descriptionStyle] overrides the `bodySm` default for data-flavored subtitles
 * (e.g. the biometrics latest-reading readout uses `monoSm`).
 */
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    descriptionStyle: TextStyle? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            if (description != null) {
                Text(
                    description,
                    style = descriptionStyle ?: Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Hf.colors.textInverse,
                checkedTrackColor = Hf.colors.accent,
            ),
        )
    }
}

/** Compact segmented control for small closed choices (units, biological sex). */
@Composable
fun <T> SegmentedChoice(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, text) ->
            val isSelected = value == selected
            Text(
                text = text,
                style = Hf.type.capsSm,
                color = if (isSelected) Hf.colors.textInverse else Hf.colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) Hf.colors.accent else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Small in-card loading row ([com.gte619n.healthfitness.ui.state.LoadingState] is for whole-screen loads). */
@Composable
fun InlineLoading(label: String = "Loading…") {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            color = Hf.colors.accent,
            strokeWidth = 2.dp,
        )
        Text(label, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
    }
}
