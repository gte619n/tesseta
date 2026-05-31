package com.gte619n.healthfitness.feature.nutrition

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** Six-nutrient progress block: consumed vs target, with a bar per nutrient. */
@Composable
fun MacroProgressCard(
    totals: Macros?,
    target: Macros?,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Text(
                "Today's macros",
                style = Hf.type.headingSm,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            NutrientRow.entries.forEach { nutrient ->
                MacroBar(
                    nutrient = nutrient,
                    consumed = nutrient.valueOf(totals),
                    target = nutrient.valueOf(target),
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun MacroBar(nutrient: NutrientRow, consumed: Double?, target: Double?) {
    val fraction = progressFraction(consumed, target)
    val rem = remaining(consumed, target)
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(nutrient.label, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
            Text(
                buildString {
                    append(nutrient.format(consumed))
                    if (target != null) {
                        append(" / ")
                        append(nutrient.format(target))
                    }
                },
                style = Hf.type.monoSm,
                color = Hf.colors.textPrimary,
            )
        }
        Spacer(Modifier.height(4.dp))
        // Track + fill. Over-target tints alert; otherwise the olive accent.
        val over = fraction != null && rem == 0.0 && (consumed ?: 0.0) > (target ?: 0.0)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(Hf.colors.canvas, RoundedCornerShape(3.dp)),
        ) {
            if (fraction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(
                            if (over) Hf.colors.alert else Hf.colors.accent,
                            RoundedCornerShape(3.dp),
                        ),
                )
            }
        }
        if (rem != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                if (rem > 0) "${nutrient.format(rem)} left" else "Target reached",
                style = Hf.type.capsSm,
                color = if (rem > 0) Hf.colors.textTertiary else Hf.colors.accentDim,
            )
        }
    }
}
