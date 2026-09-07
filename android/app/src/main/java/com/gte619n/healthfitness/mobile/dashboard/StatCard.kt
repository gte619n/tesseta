package com.gte619n.healthfitness.mobile.dashboard

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun StatCard(
    stat: Vital,
    overrideLabel: String? = null,
    valueSizeSp: Int = 21,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsLabel(overrideLabel ?: stat.label)
                Icon(
                    imageVector = stat.icon,
                    contentDescription = null,
                    tint = Hf.colors.textQuaternary,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = stat.value,
                    style = Hf.type.displayMd.copy(fontSize = valueSizeSp.sp, lineHeight = (valueSizeSp + 2).sp),
                    color = Hf.colors.textPrimary,
                )
                if (stat.unit != null) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stat.unit,
                        style = Hf.type.bodySm.copy(fontSize = 11.sp),
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    stat.delta != null -> DeltaText(stat.delta)
                    stat.pill != null -> Pill(stat.pill.first, stat.pill.second)
                    else -> Box(modifier = Modifier.width(1.dp))
                }
                Sparkline(
                    points = stat.sparkline,
                    modifier = Modifier
                        .width(40.dp)
                        .height(16.dp),
                )
            }
            if (stat.observedAt != null) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = observedLabel(stat.observedAt),
                    style = Hf.type.monoSm.copy(fontSize = 9.sp),
                    color = Hf.colors.textQuaternary,
                )
            }
        }
    }
}

private val OBSERVED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.US)

// Subtle, day-granular "recorded" label for a metric card: today / yesterday /
// Nd ago within a week, else "Sep 6". Mirrors the web dashboard's formatObserved.
internal fun observedLabel(instant: Instant, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val then = instant.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 7L -> "${days}d ago"
        else -> OBSERVED_DATE.format(then)
    }
}

@Composable
private fun DeltaText(delta: VitalDelta) {
    val color = when (delta.tone) {
        Tone.Good -> Hf.colors.good
        Tone.Alert -> Hf.colors.alert
        Tone.Warn -> Hf.colors.warn
        Tone.Neutral -> Hf.colors.textSecondary
    }
    val arrow = if (delta.direction == ArrowDir.Down) "↓" else "↑"
    Text(
        text = "$arrow ${delta.value} ${delta.window}",
        style = Hf.type.monoSm,
        color = color,
    )
}
