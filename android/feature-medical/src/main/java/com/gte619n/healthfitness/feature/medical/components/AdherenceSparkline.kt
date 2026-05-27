package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.AdherenceSummary
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/**
 * 30-day adherence sparkline — one vertical bar per day. Filled
 * (`takenColor`) when taken, gray-ish when not. The percentage label is
 * rendered on the right with the muted-mono treatment used elsewhere on
 * the card.
 */
@Composable
fun AdherenceSparkline(
    adherence: AdherenceSummary?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (adherence == null) {
            Text(
                text = "No adherence data",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        } else {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp),
            ) {
                val days = adherence.last30Days
                if (days.isEmpty()) return@Canvas
                val gap = 1.dp.toPx()
                val barWidth = (size.width - gap * (days.size - 1)) / days.size
                days.forEachIndexed { i, day ->
                    val x = i * (barWidth + gap)
                    val color = if (day.taken) takenColor else missedColor
                    drawRect(
                        color = color,
                        topLeft = Offset(x, 0f),
                        size = Size(barWidth, size.height),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${adherence.percentage.roundToInt()}%",
                style = Hf.type.monoSm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}

private val takenColor = Color(0xFF22C55E)
private val missedColor = Color(0xFFE5E4DE)
