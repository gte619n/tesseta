package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/* ----------------------------- atoms ----------------------------- */

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
            style = Hf.type.headingSm.copy(
                fontSize = if (compact) 12.sp else 13.sp,
            ),
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
    tone: Tone,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (tone) {
        Tone.Good -> Hf.colors.goodBg to Hf.colors.accentDim
        Tone.Warn -> Hf.colors.warnBg to Hf.colors.warn
        Tone.Alert -> Hf.colors.alertBg to Hf.colors.alert
        Tone.Neutral -> Hf.colors.canvas to Hf.colors.textSecondary
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

@Composable
fun Sparkline(
    points: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = Hf.colors.accent,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        val xStep = w / (points.size - 1)
        // Source mockup uses viewBox 0..20 for y.
        val sourceYMax = 20f
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, y ->
            val px = i * xStep
            val py = (y / sourceYMax) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

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
fun IconButtonChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    showDot: Boolean = false,
    size: Int = 34,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .background(Hf.colors.surface, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Hf.colors.textSecondary,
            modifier = Modifier.size(15.dp),
        )
        if (showDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 7.dp, end = 8.dp)
                    .size(6.dp)
                    .background(Hf.colors.accent, RoundedCornerShape(50))
                    .border(1.5.dp, Hf.colors.surface, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
fun AvatarSquare(initials: String, size: Int = 34, photoUrl: String? = null) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.textPrimary),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl != null) {
            HfAsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = initials,
                style = Hf.type.monoSm.copy(fontSize = 12.sp),
                color = Hf.colors.textInverse,
            )
        }
    }
}

@Composable
fun HRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Hf.colors.borderSubtle),
    )
}

@Composable
fun ChartGridDashed(
    yPositionsFrac: List<Float>,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val effect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f), 0f)
        yPositionsFrac.forEach { f ->
            val y = size.height * f
            drawLine(
                color = Color(0xFFEFE7D2),
                start = androidx.compose.ui.geometry.Offset(0f, y),
                end = androidx.compose.ui.geometry.Offset(size.width, y),
                strokeWidth = 0.5.dp.toPx(),
                pathEffect = effect,
            )
        }
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
                .fillMaxWidth(pct)
                .background(color),
        )
    }
}

@Composable
fun MonoValueText(
    primary: String,
    unit: String? = null,
    primarySizeSp: Int = 22,
    unitSizeSp: Int = 11,
    color: Color = Hf.colors.textPrimary,
    align: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = primary,
            style = Hf.type.displayMd.copy(fontSize = primarySizeSp.sp, lineHeight = (primarySizeSp + 2).sp),
            color = color,
            textAlign = align,
        )
        if (unit != null) {
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                style = Hf.type.bodySm.copy(fontSize = unitSizeSp.sp),
                color = Hf.colors.textTertiary,
            )
        }
    }
}

/** A full-width filler so a Box can be sized by its content alongside a flex region. */
@Composable
fun Filler() {
    Spacer(Modifier.fillMaxSize())
}

@Composable
fun ColumnDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(0.5.dp)
            .background(Hf.colors.borderDefault),
    )
}
