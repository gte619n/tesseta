package com.gte619n.healthfitness.feature.workouts.progression

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.domain.workouts.progression.PatternReview
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.abs
import kotlin.math.roundToInt

// The training modes offered by the segmented control, in display order.
private val MODES = listOf("GAINING", "RECOMP", "MAINTENANCE", "RECOVERY")

@Composable
fun ProgressionConsoleRoute(
    onBack: () -> Unit,
    viewModel: ProgressionConsoleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgressionConsoleScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onSelectMode = viewModel::updateMode,
    )
}

@Composable
fun ProgressionConsoleScreen(
    state: ProgressionConsoleViewModel.State,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onSelectMode: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Progression Engine",
            subtitle = "Your trends, strength, and the settings driving your numbers",
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize())
            state.error != null && state.block == null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
            else -> ConsoleBody(state = state, onSelectMode = onSelectMode)
        }
    }
}

@Composable
private fun ConsoleBody(
    state: ProgressionConsoleViewModel.State,
    onSelectMode: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // === This week ===
        item(key = "header-week") { SectionTitle(text = "This week") }
        if (state.weekReview.isEmpty()) {
            item(key = "week-empty") {
                EmptyState(
                    title = "No trends yet",
                    description = "Log a few weeks of training to see per-pattern trends here.",
                )
            }
        } else {
            items(state.weekReview, key = { it.pattern }) { review ->
                PatternReviewCard(review)
            }
        }

        // === Estimated strength (weights) ===
        item(key = "header-strength") {
            Spacer(Modifier.height(8.dp))
            SectionTitle(text = "Estimated strength")
        }
        if (state.strength.isEmpty()) {
            item(key = "strength-empty") {
                EmptyState(
                    title = "No strength estimates yet",
                    description = "Log working sets and the engine will estimate your 1-rep max per lift.",
                )
            }
        } else {
            item(key = "strength-card") { StrengthCard(state.strength) }
        }

        // === Training block ===
        item(key = "header-block") {
            Spacer(Modifier.height(8.dp))
            SectionTitle(text = "Training block")
        }
        val block = state.block
        if (block != null) {
            item(key = "block-card") {
                BlockParametersCard(
                    block = block,
                    goal = state.goal,
                    energy = state.energy,
                    updating = state.updatingMode,
                    onSelectMode = onSelectMode,
                )
            }
        }
    }
}

@Composable
private fun PatternReviewCard(review: PatternReview) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    humanize(review.pattern),
                    style = Hf.type.headingMd.copy(fontSize = 15.sp),
                    color = Hf.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                Pill(text = review.trend, tone = trendTone(review.trend))
                if (review.deload) {
                    Pill(text = "Deload", tone = HfTone.Warn)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CapsLabel("Sets")
                Text(
                    "${review.currentTarget} → ${review.proposedTarget}",
                    style = Hf.type.monoMd,
                    color = Hf.colors.textPrimary,
                )
            }
            if (review.reasoning.isNotBlank()) {
                Text(
                    review.reasoning,
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun StrengthCard(strength: List<ExerciseStrength>) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            strength.forEachIndexed { i, s ->
                if (i > 0) {
                    HorizontalDivider(
                        color = Hf.colors.borderSubtle,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            s.name,
                            style = Hf.type.bodyLg,
                            color = Hf.colors.textPrimary,
                        )
                        s.movementPattern?.let { pattern ->
                            CapsLabel(humanize(pattern), size = 9)
                        }
                    }
                    Text(
                        "${s.e1rmLbs.roundToInt()} lb",
                        style = Hf.type.monoLg.copy(fontSize = 15.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Pill(text = s.confidence, tone = confidenceTone(s.confidence))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Estimated 1-rep max per lift — the engine's core belief, and what your prescribed working weights are derived from.",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun BlockParametersCard(
    block: BlockParameters,
    goal: ProgressionConsoleViewModel.ActiveGoal?,
    energy: EnergyBalance?,
    updating: Boolean,
    onSelectMode: (String) -> Unit,
) {
    val measured = energy?.takeIf { it.hasIntakeData }?.mode
    val pinnedDiverges = block.manualOverride && measured != null && measured != block.mode

    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Mode selector — selected is evident; measured mode is marked.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Mode", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    if (block.manualOverride) Pill(text = "Pinned", tone = HfTone.Neutral)
                    if (goal != null) {
                        CapsLabel("goal: ${goal.title}", size = 9)
                    }
                }
                ModeSelector(
                    selected = block.mode,
                    measured = measured,
                    enabled = !updating,
                    onSelect = onSelectMode,
                )
                if (pinnedDiverges && measured != null) {
                    Text(
                        "Pinned to ${humanize(block.mode)} — your recent eating measures as ${humanize(measured)}.",
                        style = Hf.type.bodySm,
                        color = Hf.colors.warn,
                    )
                }

                // What the mode means + how it links to eating and the goal.
                if (isKnownMode(block.mode)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(Hf.colors.canvasMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            "${humanize(block.mode)} — ${modeBlurb(block.mode)} (${modeBand(block.mode)})",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textSecondary,
                        )
                        if (energy != null && energy.hasIntakeData) {
                            Text(
                                "Measured: eating ~${kcal(energy.meanIntakeKcal)} vs ~${kcal(energy.maintenanceKcal)} maintenance → ${humanize(energy.mode)}.",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textTertiary,
                            )
                        }
                        if (goal != null) {
                            Text(
                                "Goal: ${goal.title} (${humanize(goal.domain)}) — ${goalIntent(goal.domain)}",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textTertiary,
                            )
                        }
                        Text(
                            "Mode is chosen automatically from your energy balance and applies across every program. Pin one to override.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    }
                }
            }

            // Block targets — hairlined so they parse as a table.
            KeyValueGroup("Block targets") {
                KeyValueRow("Success criterion", humanize(block.successCriterion), divider = false)
                KeyValueRow("Expected load trend", formatLoadTrend(block.expectedDriftPerDay))
            }

            if (block.repRanges.isNotEmpty()) {
                KeyValueGroup("Rep ranges") {
                    block.repRanges.toSortedMap().entries.forEachIndexed { i, (pattern, range) ->
                        KeyValueRow(humanize(pattern), "${range.first}–${range.second}", divider = i > 0)
                    }
                }
            }

            if (block.weeklyCeiling.isNotEmpty()) {
                KeyValueGroup("Weekly set ceiling") {
                    block.weeklyCeiling.toSortedMap().entries.forEachIndexed { i, (pattern, ceiling) ->
                        KeyValueRow(humanize(pattern), "$ceiling sets", divider = i > 0)
                    }
                }
            }

            if (block.rirCaps.isNotEmpty()) {
                KeyValueGroup("RIR caps") {
                    block.rirCaps.toSortedMap().entries.forEachIndexed { i, (klass, cap) ->
                        KeyValueRow(humanize(klass), formatRir(cap), divider = i > 0)
                    }
                }
            }
        }
    }
}

/**
 * Mode picker as a 2×2 grid: selected is filled + checked, measured is tagged.
 * The grid (vs. a single row) gives each cell room for larger, taller labels —
 * "Maintenance" doesn't fit four-across at this size.
 */
@Composable
private fun ModeSelector(
    selected: String,
    measured: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MODES.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { value ->
                    ModeCell(
                        value = value,
                        isSelected = value == selected,
                        isMeasured = measured == value,
                        enabled = enabled,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.ModeCell(
    value: String,
    isSelected: Boolean,
    isMeasured: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(7.dp))
            .background(if (isSelected) Hf.colors.accent else Color.Transparent)
            .then(if (enabled) Modifier.clickable { onSelect(value) } else Modifier)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Hf.colors.textInverse,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = humanize(value),
                style = Hf.type.capsMd.copy(fontSize = 13.sp),
                color = if (isSelected) Hf.colors.textInverse else Hf.colors.textSecondary,
            )
        }
        if (isMeasured) {
            Text(
                text = "measured",
                style = Hf.type.capsSm.copy(fontSize = 9.sp),
                color = if (isSelected) Hf.colors.textInverse else Hf.colors.accentDim,
            )
        }
    }
}

@Composable
private fun KeyValueGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CapsLabel(label)
        content()
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, divider: Boolean = false) {
    if (divider) {
        HorizontalDivider(
            color = Hf.colors.borderSubtle,
            thickness = 0.5.dp,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        Text(value, style = Hf.type.monoSm, color = Hf.colors.textPrimary)
    }
}

// ── Formatting / tone ───────────────────────────────────────────────

private fun trendTone(trend: String): HfTone = when (trend.uppercase()) {
    "RISING" -> HfTone.Good
    "FALLING" -> HfTone.Alert
    "FLAT" -> HfTone.Neutral
    else -> HfTone.Neutral
}

private fun confidenceTone(confidence: String): HfTone = when (confidence.uppercase()) {
    "HIGH" -> HfTone.Good
    "LOW" -> HfTone.Warn
    else -> HfTone.Neutral
}

private fun isKnownMode(mode: String): Boolean = MODES.contains(mode.uppercase())

private fun modeBlurb(mode: String): String = when (mode.uppercase()) {
    "GAINING" -> "Pushes load up, highest weekly volume."
    "RECOMP" -> "Holds bodyweight, still adds load where you can."
    "MAINTENANCE" -> "Holds load at lower effort (RIR), flat volume."
    "RECOVERY" -> "Eases load slightly, lowest volume to protect a hard cut."
    else -> ""
}

private fun modeBand(mode: String): String = when (mode.uppercase()) {
    "GAINING" -> "Surplus ≥ +300 kcal/day"
    "RECOMP" -> "Around maintenance (±300)"
    "MAINTENANCE" -> "Modest deficit (300–500)"
    "RECOVERY" -> "Large deficit (> 500) or recovery flag"
    else -> ""
}

private fun goalIntent(domain: String): String = when (domain.uppercase()) {
    "BODY_COMPOSITION" -> "fat loss favors a deficit (Maintenance / Recovery)."
    "STRENGTH" -> "strength favors eating at or above maintenance (Recomp / Gaining)."
    "METABOLIC" -> "metabolic health usually favors maintenance."
    else -> "sets the intent your eating should serve."
}

/** "PUSH_HORIZONTAL" → "Push Horizontal"; keeps acronyms upper-cased. */
private fun humanize(enumName: String): String {
    val acronyms = setOf("RIR", "RPE", "1RM")
    return enumName.split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (acronyms.contains(word.uppercase())) word.uppercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }
}

/** e1RM drift the block expects, shown as a per-week load trend (backend carries lb/day). */
private fun formatLoadTrend(driftPerDay: Double): String {
    val perWeek = driftPerDay * 7
    if (abs(perWeek) < 0.05) return "Holding — no planned change"
    val sign = if (perWeek > 0) "+" else "−"
    return "$sign${"%.1f".format(abs(perWeek))} lb / week"
}

private fun formatRir(cap: Double): String = trimTrailingZeros(cap)

private fun trimTrailingZeros(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private fun kcal(v: Double): String = "%,d".format(v.roundToInt())

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 1500)
@Composable
private fun ProgressionConsolePreview() {
    HealthFitnessTheme {
        ProgressionConsoleScreen(
            state = ProgressionConsoleViewModel.State(
                loading = false,
                weekReview = listOf(
                    PatternReview(
                        pattern = "PUSH_HORIZONTAL",
                        trend = "RISING",
                        weeklySlopePct = 2.4,
                        fatigueIndex = 0.3,
                        currentTarget = 12,
                        proposedTarget = 14,
                        deload = false,
                        reasoning = "Volume trending up with low fatigue — add a set.",
                    ),
                ),
                strength = listOf(
                    ExerciseStrength("e1", "Conventional Deadlift", "HINGE", 405.0, "HIGH", 11),
                    ExerciseStrength("e2", "Barbell Back Squat", "SQUAT", 315.0, "HIGH", 14),
                    ExerciseStrength("e3", "Barbell Bench Press", "PUSH_HORIZONTAL", 245.0, "MEDIUM", 6),
                ),
                energy = EnergyBalance(2680.0, 2740.0, 60.0, "RECOMP", true),
                goal = ProgressionConsoleViewModel.ActiveGoal("Drop to 12% body fat", "BODY_COMPOSITION"),
                block = BlockParameters(
                    mode = "GAINING",
                    expectedDriftPerDay = 0.1,
                    successCriterion = "ADD_LOAD",
                    manualOverride = true,
                    repRanges = mapOf(
                        "PUSH_HORIZONTAL" to (6 to 10),
                        "PULL_VERTICAL" to (8 to 12),
                    ),
                    rirCaps = mapOf("COMPOUND" to 2.0, "ISOLATION" to 1.0),
                    weeklyCeiling = mapOf("PUSH_HORIZONTAL" to 18, "PULL_VERTICAL" to 20),
                ),
            ),
            onBack = {},
        )
    }
}
