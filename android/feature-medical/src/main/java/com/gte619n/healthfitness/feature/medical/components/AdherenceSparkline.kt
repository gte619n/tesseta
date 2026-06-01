package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.AdherenceSummary
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

private val TakenColor = Color(0xFF22C55E)

/**
 * 30-day adherence sparkline: one short vertical bar per day, green when the
 * dose was taken, muted gray otherwise, with a percentage label on the right.
 */
@Composable
fun AdherenceSparkline(
    adherence: AdherenceSummary?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val days = adherence?.last30Days.orEmpty()
            if (days.isEmpty()) {
                // Render 30 muted placeholder bars so the card keeps its shape.
                repeat(30) { Bar(taken = false) }
            } else {
                days.takeLast(30).forEach { Bar(taken = it.taken) }
            }
        }
        Spacer(Modifier.width(2.dp))
        Text(
            text = "${(adherence?.percentage ?: 0.0).toInt()}%",
            style = Hf.type.capsSm,
            color = Hf.colors.textSecondary,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Bar(taken: Boolean) {
    Box(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 0.5.dp)
            .height(if (taken) 16.dp else 8.dp)
            .background(
                if (taken) TakenColor else Hf.colors.muted.copy(alpha = 0.4f),
                RoundedCornerShape(1.dp),
            ),
    )
}
