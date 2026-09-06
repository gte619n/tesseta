package com.gte619n.healthfitness.feature.workouts.progression

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
            title = "Progression",
            subtitle = "Your training trends and block settings",
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
private fun BlockParametersCard(
    block: BlockParameters,
    updating: Boolean,
    onSelectMode: (String) -> Unit,
) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Mode segmented control.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mode", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                ModeToggleRow(
                    options = MODES,
                    selected = block.mode,
                    enabled = !updating,
                    onSelect = onSelectMode,
                )
            }

            // Success criterion + manual-override flag.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    CapsLabel("Success criterion")
                    Spacer(Modifier.height(2.dp))
                    Text(
                        humanize(block.successCriterion),
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textPrimary,
                    )
                }
                if (block.manualOverride) {
                    Pill(text = "Manual override", tone = HfTone.Neutral)
                }
            }

            KeyValueRow("Expected drift / day", formatDrift(block.expectedDriftPerDay))

            // Rep ranges (read-only).
            if (block.repRanges.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CapsLabel("Rep ranges")
                    block.repRanges.toSortedMap().forEach { (pattern, range) ->
                        KeyValueRow(humanize(pattern), "${range.first}–${range.second}")
                    }
                }
            }

            // Weekly set ceilings (read-only) — the other per-pattern cap.
            if (block.weeklyCeiling.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CapsLabel("Weekly set ceiling")
                    block.weeklyCeiling.toSortedMap().forEach { (pattern, ceiling) ->
                        KeyValueRow(humanize(pattern), ceiling.toString())
                    }
                }
            }

            // RIR caps (read-only).
            if (block.rirCaps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CapsLabel("RIR caps")
                    block.rirCaps.toSortedMap().forEach { (klass, cap) ->
                        KeyValueRow(humanize(klass), formatRir(cap))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        Text(value, style = Hf.type.monoSm, color = Hf.colors.textPrimary)
    }
}

/** Segmented single-select control, mirrored from settings' UnitToggleRow. */
@Composable
private fun ModeToggleRow(
    options: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { value ->
            val isSelected = value == selected
            Text(
                text = humanize(value),
                style = Hf.type.capsSm,
                color = if (isSelected) Hf.colors.textInverse else Hf.colors.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) Hf.colors.accent else Color.Transparent)
                    .then(if (enabled) Modifier.clickable { onSelect(value) } else Modifier)
                    .padding(vertical = 7.dp),
            )
        }
    }
}

private fun trendTone(trend: String): HfTone = when (trend.uppercase()) {
    "RISING" -> HfTone.Good
    "FALLING" -> HfTone.Alert
    "FLAT" -> HfTone.Neutral
    else -> HfTone.Neutral
}

/** "PUSH_HORIZONTAL" → "Push Horizontal". */
private fun humanize(enumName: String): String =
    enumName.split('_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }

private fun formatDrift(drift: Double): String {
    val sign = if (drift > 0) "+" else ""
    return "$sign${trimTrailingZeros(drift)}%"
}

private fun formatRir(cap: Double): String = trimTrailingZeros(cap)

private fun trimTrailingZeros(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 1100)
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
                    PatternReview(
                        pattern = "PULL_VERTICAL",
                        trend = "FALLING",
                        weeklySlopePct = -1.1,
                        fatigueIndex = 0.8,
                        currentTarget = 14,
                        proposedTarget = 10,
                        deload = true,
                        reasoning = "Fatigue high and volume slipping — deload this pattern.",
                    ),
                ),
                block = BlockParameters(
                    mode = "RECOMP",
                    expectedDriftPerDay = 0.0,
                    successCriterion = "HOLD_LOAD_AT_LOWER_RIR",
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
