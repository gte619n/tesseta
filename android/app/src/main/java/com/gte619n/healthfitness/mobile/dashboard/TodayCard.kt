package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun TodayCard(
    modifier: Modifier = Modifier,
    showHrInMeta: Boolean = false,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Today")
                Text(
                    text = "IN PROGRESS",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textTertiary,
                )
            }
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "CALORIES",
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = DashboardFallbacks.caloriesCurrent,
                            style = Hf.type.displayMd.copy(fontSize = 22.sp, lineHeight = 22.sp),
                            color = Hf.colors.textPrimary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "/ ${DashboardFallbacks.caloriesTarget}",
                            style = Hf.type.bodySm.copy(fontSize = 11.sp),
                            color = Hf.colors.textTertiary,
                        )
                    }
                }
                CaloriesDonut(pct = DashboardFallbacks.caloriesPct, sizeDp = 42)
            }
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardFallbacks.macros.forEachIndexed { i, macro ->
                    MacroCell(
                        label = macro.label,
                        value = macro.value,
                        unit = macro.unit,
                        pct = macro.pct,
                        color = listOf(Hf.colors.accent, Hf.colors.goodAlt, Hf.colors.muted)[i],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            HRule()
            Spacer(Modifier.height(11.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = DashboardFallbacks.workoutTitle,
                        style = Hf.type.bodyMd.copy(fontSize = 12.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (showHrInMeta) DashboardFallbacks.workoutMetaDesktop else DashboardFallbacks.workoutMetaPhone,
                        style = Hf.type.monoSm,
                        color = Hf.colors.textTertiary,
                    )
                }
                Pill("Done", Tone.Good)
            }
        }
    }
}

@Composable
private fun RowScopeWeight() = Unit

@Composable
private fun MacroCell(
    label: String,
    value: String,
    unit: String,
    pct: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = Hf.type.monoMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = unit,
                style = Hf.type.bodySm.copy(fontSize = 10.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Spacer(Modifier.height(5.dp))
        ProgressTrack(pct = pct, color = color)
    }
}

@Composable
fun CaloriesDonut(pct: Float, sizeDp: Int) {
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val stroke = 4.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            // Track
            drawArc(
                color = Color(0xFFF0EBE0),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color(0xFF5C7A2E),
                startAngle = -90f,
                sweepAngle = 360f * pct,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(pct * 100).toInt()}%",
            style = Hf.type.monoMd.copy(fontSize = if (sizeDp <= 38) 9.sp else 10.sp),
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}
