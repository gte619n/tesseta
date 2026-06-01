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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Frequency editor mirroring web's simple/PRN/Weekly UI. CYCLE is intentionally
 * not exposed (matches current web behaviour); existing CYCLE records still
 * round-trip via the domain model.
 */
@Composable
fun FrequencySelector(
    config: FrequencyConfig,
    onChange: (FrequencyConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        CapsLabel("Frequency", color = Hf.colors.textSecondary)
        Spacer(Modifier.height(6.dp))

        val options = listOf(
            FrequencyType.DAILY to "Daily",
            FrequencyType.WEEKLY to "Weekly",
            FrequencyType.MONTHLY to "Monthly",
            FrequencyType.PRN to "As needed",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (type, label) ->
                val selected = config.type == type
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) Hf.colors.accentBg else Hf.colors.surface,
                            RoundedCornerShape(7.dp),
                        )
                        .border(
                            0.5.dp,
                            if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                            RoundedCornerShape(7.dp),
                        )
                        .clickable { onChange(config.copy(type = type)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        label,
                        style = Hf.type.bodySm,
                        color = if (selected) Hf.colors.accentDim else Hf.colors.textSecondary,
                    )
                }
            }
        }

        // times-per-period stepper for DAILY / WEEKLY
        if (config.type == FrequencyType.DAILY || config.type == FrequencyType.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            val unit = if (config.type == FrequencyType.DAILY) "times per day" else "times per week"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("How often:", style = Hf.type.bodySm, color = Hf.colors.textSecondary)
                Spacer(Modifier.width(10.dp))
                Stepper(
                    value = config.timesPerPeriod ?: 1,
                    onChange = { onChange(config.copy(timesPerPeriod = it)) },
                )
                Spacer(Modifier.width(8.dp))
                Text(unit, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }

        // specific-days chips for WEEKLY
        if (config.type == FrequencyType.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            CapsLabel("On days", color = Hf.colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val selectedDays = config.specificDays.orEmpty()
                    val on = day in selectedDays
                    Box(
                        modifier = Modifier
                            .background(
                                if (on) Hf.colors.accent else Hf.colors.surface,
                                RoundedCornerShape(6.dp),
                            )
                            .border(
                                0.5.dp,
                                if (on) Hf.colors.accent else Hf.colors.borderDefault,
                                RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                val next = if (on) selectedDays - day else selectedDays + day
                                onChange(config.copy(specificDays = next.ifEmpty { null }))
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            day.name.take(1),
                            style = Hf.type.capsSm,
                            color = if (on) Hf.colors.textInverse else Hf.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Outlined.Remove, "Decrease") { onChange((value - 1).coerceAtLeast(1)) }
        Text(
            value.toString(),
            style = Hf.type.bodyMd,
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(32.dp),
        )
        StepButton(Icons.Outlined.Add, "Increase") { onChange((value + 1).coerceAtMost(12)) }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(Hf.colors.surface, RoundedCornerShape(6.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Hf.colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}
