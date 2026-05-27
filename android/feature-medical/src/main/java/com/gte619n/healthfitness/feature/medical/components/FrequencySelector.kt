package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DayOfWeek
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Segmented picker for `FrequencyType` + conditional `timesPerPeriod`
 * stepper + WEEKLY `specificDays` chip row. Matches the simple frequency
 * UI on web — CYCLE editing is intentionally hidden (frequency type
 * stays settable so existing CYCLE records round-trip, but the on/off
 * weeks editor is deferred).
 */
@Composable
fun FrequencySelector(
    config: FrequencyConfig,
    onChange: (FrequencyConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "FREQUENCY",
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SimpleFrequencies.forEach { type ->
                val active = config.type == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) Hf.colors.accentBg else Hf.colors.surface)
                        .border(
                            0.5.dp,
                            if (active) Hf.colors.accent else Hf.colors.borderDefault,
                            RoundedCornerShape(6.dp),
                        )
                        .clickable {
                            onChange(
                                config.copy(
                                    type = type,
                                    timesPerPeriod = config.timesPerPeriod ?: 1,
                                    specificDays = if (type == FrequencyType.WEEKLY) config.specificDays else null,
                                ),
                            )
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = type.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = Hf.type.bodySm,
                        color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
                    )
                }
            }
        }

        if (config.type == FrequencyType.DAILY || config.type == FrequencyType.WEEKLY) {
            Spacer(Modifier.height(12.dp))
            TimesPerPeriodStepper(
                label = if (config.type == FrequencyType.DAILY) "TIMES PER DAY" else "TIMES PER WEEK",
                value = config.timesPerPeriod ?: 1,
                onValueChange = { onChange(config.copy(timesPerPeriod = it.coerceAtLeast(1))) },
            )
        }

        if (config.type == FrequencyType.WEEKLY) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "DAYS",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DayOfWeek.values().forEach { day ->
                    val active = config.specificDays?.contains(day) == true
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (active) Hf.colors.accentBg else Hf.colors.surface)
                            .border(
                                0.5.dp,
                                if (active) Hf.colors.accent else Hf.colors.borderDefault,
                                RoundedCornerShape(4.dp),
                            )
                            .clickable {
                                val existing = config.specificDays?.toMutableList() ?: mutableListOf()
                                if (active) existing.remove(day) else existing.add(day)
                                onChange(config.copy(specificDays = existing.takeIf { it.isNotEmpty() }))
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = day.name.take(1),
                            style = Hf.type.capsSm,
                            color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimesPerPeriodStepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Text(
        text = label,
        style = Hf.type.capsSm,
        color = Hf.colors.textTertiary,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(icon = Icons.Outlined.Remove, onClick = { onValueChange(value - 1) })
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = value.toString(),
                style = Hf.type.monoMd,
                color = Hf.colors.textPrimary,
            )
        }
        StepperButton(icon = Icons.Outlined.Add, onClick = { onValueChange(value + 1) })
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Hf.colors.canvasMuted)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Hf.colors.textPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

private val SimpleFrequencies = listOf(
    FrequencyType.DAILY,
    FrequencyType.WEEKLY,
    FrequencyType.MONTHLY,
    FrequencyType.PRN,
)
