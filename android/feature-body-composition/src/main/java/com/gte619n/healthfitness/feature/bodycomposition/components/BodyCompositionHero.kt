package com.gte619n.healthfitness.feature.bodycomposition.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.bodycomposition.KG_TO_LB
import com.gte619n.healthfitness.domain.bodycomposition.kgToLb
import com.gte619n.healthfitness.ui.chart.WeightChart
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Hero card on the body-composition overview. Shows latest weight (lb),
 * body-fat %, lean mass (lb), 7d / 90d delta chips, and the 90-day
 * weight chart. Mirrors the dashboard hero from IMPL-AND-01 but reads
 * the canonical [BodyCompositionSnapshot] directly.
 */
@Composable
fun BodyCompositionHero(
    snapshot: BodyCompositionSnapshot,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            SectionTitle("Body composition")
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                HeroNumeric(
                    primary = kgToLb(snapshot.latestWeightKg)?.let {
                        String.format(Locale.US, "%.1f", it)
                    } ?: "—",
                    unit = "lb",
                    primarySizeSp = 30,
                    unitSizeSp = 12,
                )
                Box(
                    modifier = Modifier
                        .width(0.5.dp)
                        .height(34.dp)
                        .background(Hf.colors.borderDefault),
                )
                HeroNumeric(
                    primary = snapshot.latestBodyFatPercent?.let {
                        String.format(Locale.US, "%.1f", it)
                    } ?: "—",
                    unit = "% fat",
                    primarySizeSp = 15,
                    unitSizeSp = 10,
                )
                HeroNumeric(
                    primary = kgToLb(snapshot.latestLeanMassKg)?.let {
                        String.format(Locale.US, "%.1f", it)
                    } ?: "—",
                    unit = "lean",
                    primarySizeSp = 15,
                    unitSizeSp = 10,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DeltaChip(label = "7d", deltaKg = snapshot.sevenDayDeltaKg)
                DeltaChip(label = "90d", deltaKg = snapshot.ninetyDayDeltaKg)
            }
            Spacer(Modifier.height(12.dp))
            val seriesLb = snapshot.series90d
                .filter { it.metric == BodyCompositionMetric.WEIGHT_KG }
                .map { (it.value * KG_TO_LB).toFloat() }
            if (seriesLb.size >= 2) {
                val rawMin = seriesLb.min()
                val rawMax = seriesLb.max()
                val padding = max(1f, (rawMax - rawMin) * 0.15f)
                WeightChart(
                    series = seriesLb,
                    yMin = floor((rawMin - padding).toDouble()).toFloat(),
                    yMax = ceil((rawMax + padding).toDouble()).toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = "Not enough weight readings for a 90-day chart yet.",
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun HeroNumeric(
    primary: String,
    unit: String,
    primarySizeSp: Int,
    unitSizeSp: Int,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = primary,
            style = Hf.type.displayLg.copy(fontSize = primarySizeSp.sp, lineHeight = primarySizeSp.sp),
            color = Hf.colors.textPrimary,
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = unit,
            style = Hf.type.bodySm.copy(fontSize = unitSizeSp.sp),
            color = Hf.colors.textTertiary,
        )
    }
}

@Composable
private fun DeltaChip(label: String, deltaKg: Double?) {
    val deltaLb = deltaKg?.let { it * KG_TO_LB }
    val (text, color) = when {
        deltaLb == null -> "$label —" to Hf.colors.textTertiary
        deltaLb < 0 -> "$label ↓ ${String.format(Locale.US, "%.1f", abs(deltaLb))} lb" to Hf.colors.good
        deltaLb > 0 -> "$label ↑ ${String.format(Locale.US, "%.1f", deltaLb)} lb" to Hf.colors.warn
        else -> "$label 0.0 lb" to Hf.colors.textSecondary
    }
    Box(
        modifier = Modifier
            .background(Hf.colors.canvas, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = Hf.type.monoSm.copy(fontSize = 10.sp),
            color = color,
        )
    }
}
