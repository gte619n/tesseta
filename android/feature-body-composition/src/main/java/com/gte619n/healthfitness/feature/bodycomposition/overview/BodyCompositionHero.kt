package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.bodycomposition.kgToLb
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import androidx.compose.material3.Text
import kotlin.math.abs

@Composable
fun BodyCompositionHero(
    snapshot: BodyCompositionSnapshot,
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit = WeightUnit.POUNDS,
) {
    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionTitle("Body composition")

            val weightLb = kgToLb(snapshot.latestWeightKg)
            Text(
                text = weightLb?.let { UnitFormat.weight(it, weightUnit, 1) } ?: "—",
                style = Hf.type.displayMd,
                modifier = Modifier.padding(top = 8.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Metric("Body fat", snapshot.latestBodyFatPercent?.let { "${fmt(it, 1)}%" })
                Metric(
                    "Lean mass",
                    kgToLb(snapshot.latestLeanMassKg)?.let { UnitFormat.weight(it, weightUnit, 1) },
                )
                Metric("BMI", snapshot.latestBmi?.let { fmt(it, 1) })
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DeltaChip("7d", kgToLb(snapshot.sevenDayDeltaKg), weightUnit)
                DeltaChip("90d", kgToLb(snapshot.ninetyDayDeltaKg), weightUnit)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String?) {
    Column {
        CapsLabel(label)
        Text(text = value ?: "—", style = Hf.type.headingMd)
    }
}

@Composable
private fun DeltaChip(window: String, deltaLb: Double?, weightUnit: WeightUnit) {
    if (deltaLb == null) {
        Pill(text = "$window —", tone = HfTone.Neutral)
        return
    }
    val tone = when {
        deltaLb < 0 -> HfTone.Good
        deltaLb > 0 -> HfTone.Warn
        else -> HfTone.Neutral
    }
    val sign = if (deltaLb > 0) "+" else if (deltaLb < 0) "-" else ""
    val magnitude = UnitFormat.weightValueString(abs(deltaLb), weightUnit, 1)
    val label = UnitFormat.weightLabel(weightUnit)
    Pill(text = "$window $sign$magnitude $label", tone = tone)
}

internal fun fmt(value: Double, decimals: Int): String =
    String.format(java.util.Locale.US, "%.${decimals}f", value)
