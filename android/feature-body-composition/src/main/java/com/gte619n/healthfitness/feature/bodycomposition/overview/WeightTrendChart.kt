package com.gte619n.healthfitness.feature.bodycomposition.overview

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.kgToLb
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dual-series weight-trend chart drawn on a Canvas. Plots weight (solid line,
 * left axis, in the user's [weightUnit]) and — when present — body-fat percent
 * (dashed line, right axis, in %). Each series is scaled to its own min/max
 * because weight and body-fat live on different scales.
 *
 * Wrapped in an [HfCard] with a [SectionTitle] so it matches the surrounding
 * body-composition sections (hero, DEXA cards). Axis ticks, gridlines, x-axis
 * date labels and a legend make the values readable.
 *
 * @param weightSeries metric=WEIGHT_KG, oldest-first (values in KG).
 * @param bodyFatSeries metric=BODY_FAT_PERCENT, oldest-first (values in %).
 * @param weightUnit user's display unit; weight is converted KG -> lb -> unit
 *   via the same [kgToLb] + [UnitFormat] path the rest of the feature uses.
 */
@Composable
fun WeightTrendChart(
    weightSeries: List<BodyCompositionPoint>,
    bodyFatSeries: List<BodyCompositionPoint>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    // Capture composition-locals before entering the non-composable DrawScope.
    val accent = Hf.colors.accent
    val surface = Hf.colors.surface
    val bodyFatColor = Hf.colors.textSecondary
    val axisColor = Hf.colors.borderDefault
    val gridColor = Hf.colors.borderSubtle
    val labelColor = Hf.colors.textTertiary
    val labelStyle = Hf.type.capsSm.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()
    val weightLabel = UnitFormat.weightLabel(weightUnit)

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SectionTitle("Weight trend")

            if (weightSeries.size < 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Not enough data to chart yet.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
                return@Column
            }

            val hasBodyFat = bodyFatSeries.size >= 2

            // ---- Legend ----
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendSwatch(color = accent, dashed = false)
                Text("Weight", style = Hf.type.capsSm, color = Hf.colors.textSecondary)
                LegendSwatch(color = accent, dashed = true)
                Text("7-day avg", style = Hf.type.capsSm, color = Hf.colors.textSecondary)
                if (hasBodyFat) {
                    LegendSwatch(color = bodyFatColor, dashed = true)
                    Text("Body fat", style = Hf.type.capsSm, color = Hf.colors.textSecondary)
                }
            }

            // ---- Display-unit converted weight values (KG -> lb -> unit). ----
            val weightDisplay = weightSeries.map { p ->
                UnitFormat.weightValue(kgToLb(p.value) ?: 0.0, weightUnit)
            }
            val wMin = weightDisplay.min()
            val wMax = weightDisplay.max()
            val wRange = (wMax - wMin).takeIf { it > 0.0 } ?: 1.0

            val fatValues = bodyFatSeries.map { it.value }
            val fMin = if (hasBodyFat) fatValues.min() else 0.0
            val fMax = if (hasBodyFat) fatValues.max() else 1.0
            val fRange = (fMax - fMin).takeIf { it > 0.0 } ?: 1.0

            // X-axis date labels (first + last weight samples).
            val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
            val zone = ZoneId.systemDefault()
            val firstDate = weightSeries.first().sampleTime.atZone(zone).format(dateFmt)
            val lastDate = weightSeries.last().sampleTime.atZone(zone).format(dateFmt)

            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
            ) {
                val leftGutter = 38.dp.toPx()
                val rightGutter = if (hasBodyFat) 34.dp.toPx() else 8.dp.toPx()
                val topGutter = 8.dp.toPx()
                val bottomGutter = 18.dp.toPx()

                val plotLeft = leftGutter
                val plotRight = size.width - rightGutter
                val plotTop = topGutter
                val plotBottom = size.height - bottomGutter
                val plotW = (plotRight - plotLeft).takeIf { it > 0f } ?: 1f
                val plotH = (plotBottom - plotTop).takeIf { it > 0f } ?: 1f

                fun x(i: Int, n: Int): Float =
                    if (n <= 1) plotLeft else plotLeft + (i.toFloat() / (n - 1)) * plotW

                fun yWeight(v: Double): Float =
                    plotBottom - (((v - wMin) / wRange).toFloat() * plotH)

                fun yFat(v: Double): Float =
                    plotBottom - (((v - fMin) / fRange).toFloat() * plotH)

                fun drawLabel(text: String, x: Float, y: Float) {
                    val measured = textMeasurer.measure(text, style = labelStyle)
                    drawText(measured, topLeft = Offset(x, y))
                }

                // ---- Horizontal gridlines (3 lines: top / mid / bottom). ----
                val dash = PathEffect.dashPathEffect(floatArrayOf(2f, 3f), 0f)
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val py = plotTop + frac * plotH
                    drawLine(
                        color = gridColor,
                        start = Offset(plotLeft, py),
                        end = Offset(plotRight, py),
                        strokeWidth = 0.5.dp.toPx(),
                        pathEffect = dash,
                    )
                }

                // ---- Axis baselines (left + bottom). ----
                drawLine(
                    color = axisColor,
                    start = Offset(plotLeft, plotTop),
                    end = Offset(plotLeft, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = axisColor,
                    start = Offset(plotLeft, plotBottom),
                    end = Offset(plotRight, plotBottom),
                    strokeWidth = 1.dp.toPx(),
                )

                // ---- Left Y-axis tick labels (weight): max / mid / min. ----
                val wMid = (wMin + wMax) / 2.0
                fun wTick(v: Double) = "${v.toInt()} $weightLabel"
                drawLabel(wTick(wMax), 0f, plotTop - 2.dp.toPx())
                drawLabel(wTick(wMid), 0f, yWeight(wMid) - 6.dp.toPx())
                drawLabel(wTick(wMin), 0f, plotBottom - 12.dp.toPx())

                // ---- Right Y-axis tick labels (body fat %): max / mid / min. ----
                if (hasBodyFat) {
                    val fMid = (fMin + fMax) / 2.0
                    fun fTick(v: Double) = "${v.toInt()}%"
                    val rx = plotRight + 4.dp.toPx()
                    drawLabel(fTick(fMax), rx, plotTop - 2.dp.toPx())
                    drawLabel(fTick(fMid), rx, yFat(fMid) - 6.dp.toPx())
                    drawLabel(fTick(fMin), rx, plotBottom - 12.dp.toPx())
                }

                // ---- X-axis date labels (first + last). ----
                drawLabel(firstDate, plotLeft, plotBottom + 4.dp.toPx())
                val lastMeasured = textMeasurer.measure(lastDate, style = labelStyle)
                drawLabel(
                    lastDate,
                    plotRight - lastMeasured.size.width,
                    plotBottom + 4.dp.toPx(),
                )

                // Weight points in plot space (reused for the area, line + marker).
                val weightPts = weightDisplay.mapIndexed { i, v ->
                    Offset(x(i, weightDisplay.size), yWeight(v))
                }

                // ---- Soft area fill under the weight line. ----
                val areaPath = Path().apply {
                    weightPts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                    lineTo(weightPts.last().x, plotBottom)
                    lineTo(weightPts.first().x, plotBottom)
                    close()
                }
                drawPath(path = areaPath, color = accent, alpha = 0.06f)

                // ---- Weight line (solid, accent). ----
                val weightPath = Path().apply {
                    weightPts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
                }
                drawPath(
                    path = weightPath,
                    color = accent,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )

                // ---- 7-day moving average (dashed, faint accent). ----
                val maPath = Path().apply {
                    movingAverage(weightDisplay, 7).forEachIndexed { i, v ->
                        val px = x(i, weightDisplay.size)
                        val py = yWeight(v)
                        if (i == 0) moveTo(px, py) else lineTo(px, py)
                    }
                }
                drawPath(
                    path = maPath,
                    color = accent,
                    alpha = 0.45f,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f), 0f),
                    ),
                )

                // ---- Body-fat line (dashed, secondary). ----
                if (hasBodyFat) {
                    val fatPath = Path().apply {
                        fatValues.forEachIndexed { i, v ->
                            val px = x(i, fatValues.size)
                            val py = yFat(v)
                            if (i == 0) moveTo(px, py) else lineTo(px, py)
                        }
                    }
                    drawPath(
                        path = fatPath,
                        color = bodyFatColor,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                        ),
                    )
                }

                // ---- Latest-weight marker (accent dot with a surface halo). ----
                weightPts.lastOrNull()?.let { last ->
                    drawCircle(color = surface, radius = 4.5.dp.toPx(), center = last)
                    drawCircle(color = accent, radius = 3.dp.toPx(), center = last)
                }
            }
        }
    }
}

/**
 * Trailing simple moving average over [window] samples (mirrors web's
 * `movingAverage` in `lib/chart.ts`): each point averages itself and up to the
 * preceding `window - 1` samples, so the line is defined from the first point.
 */
private fun movingAverage(series: List<Double>, window: Int): List<Double> =
    series.indices.map { i ->
        val start = maxOf(0, i - (window - 1))
        series.subList(start, i + 1).average()
    }

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean) {
    Box(modifier = Modifier.size(width = 16.dp, height = 8.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.width(16.dp).height(8.dp)) {
            val cy = size.height / 2f
            drawLine(
                color = color,
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4f, 3f), 0f) else null,
            )
        }
    }
}
