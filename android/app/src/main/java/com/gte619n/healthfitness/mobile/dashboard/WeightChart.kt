package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Weight trend over the last 90 days. The mockup's IIFE script computes the
 * polyline in JS at runtime; this composable does the same math in Compose
 * Canvas with the same paddings so the visual is byte-equivalent.
 */
@Composable
fun WeightChart(modifier: Modifier = Modifier) {
    val series = DashboardFixtures.weightSeries
    val ma = movingAverage(series, 7)
    // Capture composition-local colors before entering the non-composable DrawScope.
    val borderSubtle = Hf.colors.borderSubtle
    val accent = Hf.colors.accent
    val accentArea = accent.copy(alpha = 0.06f)
    val maColor = Hf.colors.textPrimary.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width
            val h = size.height
            val yMin = 188f
            val yMax = 194f
            val padX = w * (26f / 600f)
            val padBottom = h * (22f / 140f)
            val padTop = h * (14f / 140f)
            val chartW = w - padX - (12f / 600f) * w
            val chartH = h - padTop - padBottom

            fun x(i: Int) = padX + (i.toFloat() / (series.size - 1)) * chartW
            fun y(v: Float) = padTop + ((yMax - v) / (yMax - yMin)) * chartH

            val gridYValues = listOf(194f, 192f, 190f, 188f)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f), 0f)
            gridYValues.drop(1).forEach { v ->
                val py = y(v)
                drawLine(
                    color = borderSubtle,
                    start = Offset(0f, py),
                    end = Offset(w, py),
                    strokeWidth = 0.5.dp.toPx(),
                    pathEffect = dashEffect,
                )
            }

            val areaPath = Path().apply {
                moveTo(x(0), y(series.first()))
                for (i in 1 until series.size) lineTo(x(i), y(series[i]))
                lineTo(x(series.size - 1), padTop + chartH)
                lineTo(x(0), padTop + chartH)
                close()
            }
            drawPath(areaPath, color = accentArea)

            val linePath = Path().apply {
                moveTo(x(0), y(series.first()))
                for (i in 1 until series.size) lineTo(x(i), y(series[i]))
            }
            drawPath(
                path = linePath,
                color = accent,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            val maPath = Path().apply {
                moveTo(x(0), y(ma.first()))
                for (i in 1 until ma.size) lineTo(x(i), y(ma[i]))
            }
            drawPath(
                path = maPath,
                color = maColor,
                style = Stroke(
                    width = 1.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                ),
            )

            val cx = x(series.size - 1)
            val cy = y(series.last())
            drawCircle(
                color = Color.White,
                radius = 5.5.dp.toPx(),
                center = Offset(cx, cy),
            )
            drawCircle(
                color = accent,
                radius = 3.5.dp.toPx(),
                center = Offset(cx, cy),
            )
        }
    }
}

private fun movingAverage(series: List<Float>, window: Int): List<Float> {
    return series.indices.map { i ->
        val start = maxOf(0, i - (window - 1))
        val slice = series.subList(start, i + 1)
        slice.sum() / slice.size
    }
}
