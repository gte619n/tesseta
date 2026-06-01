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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/**
 * Daily summary card: calories as the big hero number with goal + remaining,
 * then protein / carbs / sugar as the secondary tier, and fat / fibre beneath.
 * Each nutrient shows consumed-vs-goal and a progress bar so the user can see
 * how the day is tracking against their targets at a glance.
 */
@Composable
fun MacroProgressCard(
    totals: Macros?,
    target: Macros?,
    modifier: Modifier = Modifier,
) {
    val calConsumed = totals?.caloriesKcal ?: 0.0
    val calTarget = target?.caloriesKcal
    val calFraction = progressFraction(calConsumed, calTarget)

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            // ── Calories hero ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("CALORIES", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "${calConsumed.roundToInt()}",
                            style = Hf.type.headingLg.copy(
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Hf.colors.textPrimary,
                        )
                        if (calTarget != null) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "/ ${calTarget.roundToInt()} kcal",
                                style = Hf.type.monoSm,
                                color = Hf.colors.textTertiary,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }
                }
                if (calTarget != null) {
                    val diff = (calTarget - calConsumed).roundToInt()
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${kotlin.math.abs(diff)}",
                            style = Hf.type.headingMd,
                            color = Hf.colors.textPrimary,
                        )
                        Text(
                            if (diff >= 0) "kcal left" else "kcal over",
                            style = Hf.type.capsSm,
                            color = Hf.colors.textTertiary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressTrack(fraction = calFraction, over = isOver(calConsumed, calTarget))

            // ── Primary macros: protein / carbs / sugar ───────────────────
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MacroMini(NutrientRow.PROTEIN, totals, target, Modifier.weight(1f))
                MacroMini(NutrientRow.CARBS, totals, target, Modifier.weight(1f))
                MacroMini(NutrientRow.SUGAR, totals, target, Modifier.weight(1f))
            }

            // ── Secondary macros: fat / fibre ─────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MacroMini(NutrientRow.FAT, totals, target, Modifier.weight(1f))
                MacroMini(NutrientRow.FIBER, totals, target, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MacroMini(
    nutrient: NutrientRow,
    totals: Macros?,
    target: Macros?,
    modifier: Modifier = Modifier,
) {
    val consumed = nutrient.valueOf(totals)
    val tgt = nutrient.valueOf(target)
    Column(modifier = modifier) {
        Text(nutrient.label, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(3.dp))
        Text(
            buildString {
                append((consumed ?: 0.0).roundToInt())
                if (tgt != null) append("/${tgt.roundToInt()}")
            },
            style = Hf.type.monoSm,
            color = Hf.colors.textPrimary,
        )
        Spacer(Modifier.height(5.dp))
        ProgressTrack(
            fraction = progressFraction(consumed, tgt),
            over = isOver(consumed ?: 0.0, tgt),
        )
    }
}

/** A thin rounded track with an accent fill; tints alert when over target. */
@Composable
private fun ProgressTrack(fraction: Float?, over: Boolean) {
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
}

private fun isOver(consumed: Double, target: Double?): Boolean =
    target != null && target > 0.0 && consumed > target
