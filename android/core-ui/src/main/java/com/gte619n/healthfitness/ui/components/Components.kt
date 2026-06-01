package com.gte619n.healthfitness.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// Shared design-system atoms, mirrored from the app/dashboard package so that
// feature modules (which cannot depend on :app) can reuse the same look.
// Tokens come from core-ui's HfColors / HfTypography.

/** Semantic color tone for pills and accents. */
enum class HfTone { Good, Warn, Alert, Neutral }

@Composable
fun HfCard(
    modifier: Modifier = Modifier,
    // Transparent cards (no surface fill, border only) are the rule for
    // settings/profile/form screens — they sit on the canvas, not a white
    // surface. The filled variant is for data/dashboard cards. See android/CLAUDE.md.
    transparent: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .then(
                if (transparent) Modifier
                else Modifier.background(Hf.colors.surface, RoundedCornerShape(10.dp)),
            ),
    ) {
        content()
    }
}

/**
 * Canonical screen header used by every pushed (non-dashboard) screen: a back
 * affordance, a title, and an optional subtitle, sitting on the canvas. Extracted
 * from the per-feature copies so the chrome stays consistent app-wide.
 *
 * - [onBack]: when non-null, renders the leading back arrow wired to it. Omit it
 *   for screens that genuinely can't be backed out of (none currently).
 * - [trailing]: optional actions rendered at the end of the header row (e.g. an
 *   Edit icon, a "New goal" action). When present, the title/subtitle column
 *   takes the remaining width so the actions sit flush right.
 *
 * Functional sub-headers (date navigation, tabs, etc.) belong in a SEPARATE row
 * below this header — keep them out of the title/back row so it reads identically
 * across screens. See android/CLAUDE.md for the back-affordance convention.
 */
@Composable
fun HfScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (onBack != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(20.dp).clickable { onBack() },
            )
        }
        Column(modifier = if (trailing != null) Modifier.weight(1f) else Modifier) {
            Text(
                text = title,
                style = Hf.type.headingLg.copy(fontSize = 20.sp),
                color = Hf.colors.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SectionTitle(text: String, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .height(if (compact) 11.dp else 12.dp)
                .width(3.dp)
                .background(Hf.colors.accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = Hf.type.headingSm.copy(fontSize = if (compact) 12.sp else 13.sp),
            color = Hf.colors.textPrimary,
        )
    }
}

@Composable
fun CapsLabel(
    text: String,
    color: Color = Hf.colors.textTertiary,
    size: Int = 10,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = Hf.type.capsMd.copy(fontSize = size.sp),
        color = color,
        modifier = modifier,
    )
}

@Composable
fun Pill(
    text: String,
    tone: HfTone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        HfTone.Good -> Hf.colors.goodBg to Hf.colors.accentDim
        HfTone.Warn -> Hf.colors.warnBg to Hf.colors.warn
        HfTone.Alert -> Hf.colors.alertBg to Hf.colors.alert
        HfTone.Neutral -> Hf.colors.canvas to Hf.colors.textSecondary
    }
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = Hf.type.capsSm,
            color = fg,
        )
    }
}

/** Fills a horizontal progress bar with a colored leading section. */
@Composable
fun ProgressTrack(
    pct: Float,
    color: Color,
    heightDp: Int = 2,
    trackColor: Color = Hf.colors.canvas,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Box(
        modifier = modifier
            .height(heightDp.dp)
            .background(trackColor),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(pct.coerceIn(0f, 1f))
                .background(color),
        )
    }
}
