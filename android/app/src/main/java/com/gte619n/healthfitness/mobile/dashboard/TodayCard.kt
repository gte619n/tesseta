package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

// A single macro tier cell for the Today card, derived from live totals (or an
// em-dash placeholder while the day's nutrition is still loading).
private data class TodayMacro(val label: String, val value: String, val pct: Float)

// Placeholder shown while the dashboard load/sync is still in flight. We never
// show fabricated numbers in that window — a dash makes "no data yet" obvious.
private const val DASH = "—"

/** Group-separated calories, e.g. 1247.0 → "1,247". */
private fun formatCalories(kcal: Double): String = "%,d".format(kcal.roundToInt())

/** A macro's grams, rounded for the compact cell (null/absent → "0"). */
private fun macroGrams(value: Double?): String = (value ?: 0.0).roundToInt().toString()

/**
 * Consumed-vs-target fraction in [0,1] for the donut / progress tracks. No
 * (positive) target → 0 so the ring reads empty rather than guessing.
 */
private fun macroFraction(consumed: Double?, target: Double?): Float {
    if (target == null || target <= 0.0) return 0f
    return ((consumed ?: 0.0) / target).toFloat().coerceIn(0f, 1f)
}

@Composable
fun TodayCard(
    modifier: Modifier = Modifier,
    // Today's logged nutrition (totals + target). Null while the dashboard load
    // is still in flight (or it errored). In that window we render em-dash
    // placeholders rather than fabricated fixtures, so nothing fake shows while
    // the data is syncing. Once loaded, an empty day reads as real 0s.
    nutrition: NutritionDay? = null,
) {
    val loaded = nutrition != null
    val totals = nutrition?.totals
    val target = nutrition?.target
    val caloriesCurrent =
        if (!loaded) DASH else formatCalories(totals?.caloriesKcal ?: 0.0)
    val caloriesTarget =
        if (!loaded) DASH else target?.caloriesKcal?.let { formatCalories(it) } ?: DASH
    val caloriesPct =
        if (!loaded) 0f else macroFraction(totals?.caloriesKcal, target?.caloriesKcal)
    val macros: List<TodayMacro> = if (loaded) {
        listOf(
            TodayMacro("Protein", macroGrams(totals?.proteinGrams), macroFraction(totals?.proteinGrams, target?.proteinGrams)),
            TodayMacro("Carbs", macroGrams(totals?.carbsGrams), macroFraction(totals?.carbsGrams, target?.carbsGrams)),
            TodayMacro("Fat", macroGrams(totals?.fatGrams), macroFraction(totals?.fatGrams, target?.fatGrams)),
        )
    } else {
        listOf(
            TodayMacro("Protein", DASH, 0f),
            TodayMacro("Carbs", DASH, 0f),
            TodayMacro("Fat", DASH, 0f),
        )
    }
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Today")
                Text(
                    text = "IN PROGRESS",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textTertiary,
                )
            }
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "CALORIES",
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = caloriesCurrent,
                            style = Hf.type.displayMd.copy(fontSize = 22.sp, lineHeight = 22.sp),
                            color = Hf.colors.textPrimary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "/ $caloriesTarget",
                            style = Hf.type.bodySm.copy(fontSize = 11.sp),
                            color = Hf.colors.textTertiary,
                        )
                    }
                }
                CaloriesDonut(pct = caloriesPct, sizeDp = 42)
            }
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                macros.forEachIndexed { i, macro ->
                    MacroCell(
                        label = macro.label,
                        value = macro.value,
                        unit = "g",
                        pct = macro.pct,
                        color = listOf(Hf.colors.accent, Hf.colors.goodAlt, Hf.colors.muted)[i],
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            HRule()
            Spacer(Modifier.height(11.dp))
            // Workouts aren't wired to a data source yet, so we say so plainly
            // rather than showing a fabricated session.
            Text(
                text = "No workout data available",
                style = Hf.type.bodySm.copy(fontSize = 12.sp),
                color = Hf.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun RowScopeWeight() = Unit

@Composable
private fun MacroCell(
    label: String,
    value: String,
    unit: String,
    pct: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = Hf.type.monoMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = unit,
                style = Hf.type.bodySm.copy(fontSize = 10.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Spacer(Modifier.height(5.dp))
        ProgressTrack(pct = pct, color = color)
    }
}

@Composable
fun CaloriesDonut(pct: Float, sizeDp: Int) {
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(sizeDp.dp)) {
            val stroke = 4.dp.toPx()
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            // Track
            drawArc(
                color = Color(0xFFF0EBE0),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke),
            )
            drawArc(
                color = Color(0xFF5C7A2E),
                startAngle = -90f,
                sweepAngle = 360f * pct,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "${(pct * 100).toInt()}%",
            style = Hf.type.monoMd.copy(fontSize = if (sizeDp <= 38) 9.sp else 10.sp),
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}
