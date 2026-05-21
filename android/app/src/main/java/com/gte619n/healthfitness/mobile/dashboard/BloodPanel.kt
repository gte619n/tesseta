package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun BloodPanel(
    showRangeLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Blood panel")
                Text(
                    text = DashboardFixtures.bloodPanelDate,
                    style = Hf.type.monoSm.copy(fontSize = 9.sp),
                    color = Hf.colors.textTertiary,
                )
            }
            Spacer(Modifier.height(11.dp))
            DashboardFixtures.bloodMarkers.forEachIndexed { i, m ->
                MarkerRow(marker = m, showLabels = showRangeLabels)
                if (i != DashboardFixtures.bloodMarkers.size - 1) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun MarkerRow(
    marker: DashboardFixtures.BloodMarker,
    showLabels: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = marker.name,
                style = Hf.type.bodyMd.copy(fontSize = 11.sp),
                color = Hf.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = marker.value,
                    style = Hf.type.monoMd.copy(fontSize = 12.sp),
                    color = when (marker.tone) {
                        Tone.Warn -> Hf.colors.warn
                        Tone.Alert -> Hf.colors.alert
                        Tone.Good -> Hf.colors.good
                        Tone.Neutral -> Hf.colors.textPrimary
                    },
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = marker.unit,
                    style = Hf.type.bodySm.copy(fontSize = 9.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        RangeBar(goodPct = marker.goodFillPct, tickPct = marker.tickPct)
        if (showLabels) {
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(marker.labels.first, style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
                Text(marker.labels.second, style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.good)
                Text(marker.labels.third, style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
            }
        }
    }
}

@Composable
private fun RangeBar(goodPct: Float, tickPct: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(Hf.colors.canvas),
    ) {
        // good-fill segment
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(goodPct)
                .background(Hf.colors.accentBg),
        )
        // tick mark
        TickMark(tickPct = tickPct)
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
        val placeable = measurables[0].measure(Constraints.fixed(2.dp.roundToPx(), constraints.maxHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val xCenter = (constraints.maxWidth * tickPct).toInt()
            placeable.placeRelative(xCenter - placeable.width / 2, 0)
        }
    }
}
