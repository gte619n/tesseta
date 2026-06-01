package com.gte619n.healthfitness.feature.blood.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Tracked-markers grid cell: marker label, latest value (colour-coded by
 * in/out-of-range), unit, reference bar and sample date.
 */
@Composable
fun MarkerCard(
    latest: LatestMarker,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            CapsLabel(MarkerCatalog.displayName(latest.marker))
            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val valueColor = valueColor(latest)
                Text(
                    text = latest.value?.let { formatValue(it) } ?: "—",
                    style = Hf.type.displaySm,
                    color = valueColor,
                    fontWeight = FontWeight.SemiBold,
                )
                if (latest.unit.isNotBlank()) {
                    Text(
                        text = latest.unit,
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            latest.reference?.let { ref ->
                Spacer(Modifier.height(8.dp))
                MarkerReferenceBar(value = latest.value, reference = ref)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = latest.sampleDate?.let { "Sampled ${it.format(dateFmt)}" } ?: "No readings yet",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

@Composable
private fun valueColor(latest: LatestMarker): androidx.compose.ui.graphics.Color {
    val value = latest.value ?: return Hf.colors.textTertiary
    if (latest.flag == ExtractedMarker.Flag.H || latest.flag == ExtractedMarker.Flag.L) {
        return Hf.colors.alert
    }
    val ref = latest.reference ?: return Hf.colors.textPrimary
    val good = when (ref.orientation) {
        ReferenceRange.Orientation.LOWER_IS_BETTER -> value <= ref.goodThreshold
        ReferenceRange.Orientation.HIGHER_IS_BETTER -> value >= ref.goodThreshold
    }
    return if (good) Hf.colors.good else Hf.colors.alert
}
