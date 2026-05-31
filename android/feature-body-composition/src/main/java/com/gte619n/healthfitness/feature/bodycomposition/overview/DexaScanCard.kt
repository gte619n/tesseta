package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun DexaScanCard(
    summary: DexaScanSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit = WeightUnit.POUNDS,
) {
    HfCard(modifier = modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = summary.measuredOn?.format(DATE_FMT) ?: "Unknown date",
                style = Hf.type.headingSm,
            )
            summary.sourceFacility?.let {
                Text(text = it, style = Hf.type.bodySm, modifier = Modifier.padding(top = 2.dp))
            }
            CapsLabel(
                text = "Total mass",
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = summary.totalMassLb?.let { UnitFormat.weight(it, weightUnit, 1) } ?: "—",
                style = Hf.type.monoMd,
            )
            CapsLabel(
                text = "Body fat",
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = summary.totalBodyFatPercent?.let { "${fmt(it, 1)}%" } ?: "—",
                style = Hf.type.monoMd,
            )
        }
    }
}
