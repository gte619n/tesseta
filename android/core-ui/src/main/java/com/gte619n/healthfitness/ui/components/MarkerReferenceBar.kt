package com.gte619n.healthfitness.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Shared blood-marker reference bar (IMPL-AND-04 Q7).
 *
 * A thin 3.dp track with a "good zone" fill segment positioned by
 * [goodLeftPct] (left offset, 0..1) and sized by [goodFillPct] (width, 0..1),
 * plus a 2.dp tick centered at [tickPct] (0..1). Promoted from the dashboard
 * BloodPanel's private RangeBar so both the dashboard and feature-blood draw
 * the same bar.
 */
@Composable
fun MarkerReferenceBar(
    goodLeftPct: Float,
    goodFillPct: Float,
    tickPct: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Hf.colors.canvas),
    ) {
        GoodZone(goodLeftPct = goodLeftPct, goodFillPct = goodFillPct)
        TickMark(tickPct = tickPct)
    }
}

@Composable
private fun GoodZone(goodLeftPct: Float, goodFillPct: Float) {
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(Hf.colors.accentBg),
            )
        },
    ) { measurables, constraints ->
        val totalWidth = constraints.maxWidth
        val segWidth = (totalWidth * goodFillPct).toInt().coerceIn(0, totalWidth)
        val left = (totalWidth * goodLeftPct).toInt().coerceIn(0, totalWidth - segWidth)
        val placeable = measurables[0].measure(
            Constraints.fixed(segWidth, constraints.maxHeight),
        )
        layout(totalWidth, constraints.maxHeight) {
            placeable.placeRelative(left, 0)
        }
    }
}

@Composable
private fun TickMark(tickPct: Float) {
    Layout(
        content = {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Hf.colors.textPrimary),
            )
        },
    ) { measurables, constraints ->
        val placeable = measurables[0].measure(
            Constraints.fixed(2.dp.roundToPx(), constraints.maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val xCenter = (constraints.maxWidth * tickPct).toInt()
            placeable.placeRelative(xCenter - placeable.width / 2, 0)
        }
    }
}
