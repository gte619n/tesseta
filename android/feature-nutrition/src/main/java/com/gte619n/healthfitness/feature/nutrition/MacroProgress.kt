package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * then protein / carbs / fat as the primary macro tier. Sugar + fibre live
 * behind a "Sugar & fibre" expander so the default view stays focused on the
 * three macros most people track. Each nutrient shows consumed-vs-goal and a
 * progress bar so the day's tracking is legible at a glance.
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
    var showMore by remember { mutableStateOf(false) }

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

            // ── Primary macros: protein / carbs / fat ─────────────────────
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MacroMini(NutrientRow.PROTEIN, totals, target, Modifier.weight(1f))
                MacroMini(NutrientRow.CARBS, totals, target, Modifier.weight(1f))
                MacroMini(NutrientRow.FAT, totals, target, Modifier.weight(1f))
            }

            // ── Sugar & fibre, behind an expander ─────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showMore = !showMore }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (showMore) "Hide sugar & fibre" else "Sugar & fibre",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textSecondary,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    imageVector = if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (showMore) "Hide sugar and fibre" else "Show sugar and fibre",
                    tint = Hf.colors.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            AnimatedVisibility(visible = showMore) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MacroMini(NutrientRow.SUGAR, totals, target, Modifier.weight(1f))
                    MacroMini(NutrientRow.FIBER, totals, target, Modifier.weight(1f))
                }
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
                if (nutrient.grams) append(" g")
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
