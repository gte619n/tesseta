package com.gte619n.healthfitness.feature.blood.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.blood.MarkerHistoryPoint
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.abs

/**
 * Full marker history chart: a polyline of one-point-per-day values over the
 * reference-range band. Pattern mirrors the dashboard's WeightChart Canvas.
 *
 * History is expected oldest → newest; multiple readings per day are already
 * deduped upstream (last value wins) by
 * [com.gte619n.healthfitness.domain.blood.LatestMarkers].
 */
@Composable
fun MarkerHistoryChart(
    history: List<MarkerHistoryPoint>,
    reference: ReferenceRange?,
    modifier: Modifier = Modifier,
    title: String = "12-month history",
) {
    val points = history.sortedBy { it.date }

    // Capture composition-local colors before entering the non-composable DrawScope.
    val lineColor = Hf.colors.accent
    val bandColor = Hf.colors.goodBg
    val gridColor = Hf.colors.borderSubtle

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            SectionTitle(title)

            if (points.size < 2) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Not enough data to chart yet.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
                return@Column
            }

            val values = points.map { it.value }
            val refLo = reference?.displayMin
            val refHi = reference?.displayMax
            val minV = listOfNotNull(values.min(), refLo).min()
            val maxV = listOfNotNull(values.max(), refHi).max()
            val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .padding(top = 8.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                    val w = size.width
                    val h = size.height

                    fun yFor(v: Double): Float = (h - ((v - minV) / span * h)).toFloat()

                    if (refLo != null && refHi != null) {
                        val top = yFor(refHi)
                        val bottom = yFor(refLo)
                        drawRect(
                            color = bandColor,
                            topLeft = Offset(0f, minOf(top, bottom)),
                            size = Size(w, abs(bottom - top)),
                        )
                    }

                    drawLine(
                        color = gridColor,
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1f,
                    )

                    val stepX = if (points.size > 1) w / (points.size - 1) else w
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val x = stepX * i
                        val y = yFor(p.value)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path = path, color = lineColor, style = Stroke(width = 3f))

                    points.forEachIndexed { i, p ->
                        drawCircle(color = lineColor, radius = 4f, center = Offset(stepX * i, yFor(p.value)))
                    }
                }
            }
        }
    }
}
