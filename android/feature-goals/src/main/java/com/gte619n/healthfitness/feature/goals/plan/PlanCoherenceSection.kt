package com.gte619n.healthfitness.feature.goals.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Goal-scoped plan-coherence: how this goal's program, calorie target, today's
 * eating, and the engine's measured energy state line up — and where they don't.
 * Drop into the goal roadmap; it drives itself from [PlanCoherenceViewModel].
 */
@Composable
fun PlanCoherenceSection(
    modifier: Modifier = Modifier,
    viewModel: PlanCoherenceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chain = state.chain ?: return
    PlanCoherenceContent(
        chain = chain,
        flags = state.flags,
        pending = state.pendingAction,
        onReconcile = viewModel::reconcile,
        modifier = modifier,
    )
}

/** Stateless coherence UI — driven by [PlanCoherenceSection] in the app, and by
 *  the debug preview harness with sample data. */
@Composable
fun PlanCoherenceContent(
    chain: PlanChain,
    flags: List<PlanFlag>,
    pending: PlanActionKind?,
    onReconcile: (PlanActionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(text = "Coherence")
        CoherenceCard(flags = flags, pending = pending, onReconcile = onReconcile)
        Spacer(Modifier.height(4.dp))
        SectionTitle(text = "The chain")
        ChainCard(chain)
    }
}

@Composable
private fun CoherenceCard(
    flags: List<PlanFlag>,
    pending: PlanActionKind?,
    onReconcile: (PlanActionKind) -> Unit,
) {
    val actionable = flags.count { it.severity != PlanSeverity.INFO }
    val synced = actionable == 0
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Status header.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconChip(
                    icon = if (synced) Icons.Outlined.CheckCircle else Icons.Filled.ErrorOutline,
                    bg = if (synced) Hf.colors.goodBg else Hf.colors.warnBg,
                    tint = if (synced) Hf.colors.accentDim else Hf.colors.warn,
                )
                Column {
                    Text(
                        if (synced) "In sync" else "$actionable to reconcile",
                        style = Hf.type.headingMd.copy(fontSize = 13.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Text(
                        if (synced) {
                            "Your goal, program, calorie target, and measured energy balance agree."
                        } else {
                            "Where your goal, program, calorie target, and eating don't line up."
                        },
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            flags.forEach { flag ->
                HorizontalDivider(color = Hf.colors.borderSubtle, thickness = 0.5.dp)
                FlagRow(flag = flag, pending = pending == flag.action, onReconcile = onReconcile)
            }
        }
    }
}

@Composable
private fun FlagRow(flag: PlanFlag, pending: Boolean, onReconcile: (PlanActionKind) -> Unit) {
    val (chipBg, tint) = when (flag.severity) {
        PlanSeverity.ACTION -> Hf.colors.accentBg to Hf.colors.accentDim
        PlanSeverity.WARN -> Hf.colors.warnBg to Hf.colors.warn
        PlanSeverity.INFO -> Hf.colors.canvasMuted to Hf.colors.neutral
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconChip(icon = iconFor(flag.icon), bg = chipBg, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(flag.title, style = Hf.type.headingMd.copy(fontSize = 13.sp), color = Hf.colors.textPrimary)
            Text(flag.detail, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            if (flag.hint != null) {
                Text(flag.hint, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
        if (flag.action != null && flag.actionLabel != null) {
            ReconcileButton(
                label = if (pending) "…" else flag.actionLabel,
                primary = flag.severity == PlanSeverity.ACTION,
                enabled = !pending,
                onClick = { onReconcile(flag.action) },
            )
        }
    }
}

@Composable
private fun ReconcileButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val base = Modifier
        .clip(RoundedCornerShape(7.dp))
        .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 10.dp, vertical = 6.dp)
    if (primary) {
        Row(modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Hf.colors.accent).then(base)) {
            Text(label, style = Hf.type.capsSm, color = Hf.colors.textInverse)
        }
    } else {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(7.dp))
                .then(base),
        ) {
            Text(label, style = Hf.type.capsSm, color = Hf.colors.textPrimary)
        }
    }
}

@Composable
private fun IconChip(icon: ImageVector, bg: Color, tint: Color) {
    Row(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(bg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChainCard(chain: PlanChain) {
    val targetKcal = kcalOf(chain.target)
    val intakeKcal = kcalOf(chain.todayTotals)
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            ChainRow("Goal", chain.goalTitle ?: "None", chain.goalDomain?.let { titleCase(it) })
            ChainDivider()
            ChainRow(
                "Program",
                chain.programTitle ?: "None",
                chain.phaseTitle?.let { "Phase: $it" },
            )
            ChainDivider()
            ChainRow(
                "Calorie target",
                if (targetKcal != null) "${fmt(targetKcal)} kcal" else "Not set",
                macroLine(chain.target),
            )
            ChainDivider()
            ChainRow(
                "Today's intake",
                if (intakeKcal != null) "${fmt(intakeKcal)} kcal" else "Nothing logged",
                if (targetKcal != null && intakeKcal != null) {
                    "${fmt(max(0.0, targetKcal - intakeKcal))} kcal left"
                } else {
                    null
                },
            )
            ChainDivider()
            val energy = chain.energy
            ChainRow(
                "Engine · maintenance",
                if (energy?.hasIntakeData == true) "~${fmt(energy.maintenanceKcal)} kcal" else "Warming up",
                if (energy?.hasIntakeData == true) {
                    "Eating ~${fmt(energy.meanIntakeKcal)} · ${describeBalance(energy.balanceKcal)}"
                } else {
                    "Needs more logged days to measure"
                },
            )
            ChainDivider()
            ChainRow(
                "Engine · mode",
                chain.blockMode?.let { titleCase(it) } ?: "—",
                if (chain.blockManualOverride) "Pinned manually" else "Auto",
            )
        }
    }
}

@Composable
private fun ChainRow(label: String, value: String, sub: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CapsLabel(label, modifier = Modifier.width(120.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(value, style = Hf.type.bodyLg, color = Hf.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) {
                Text(sub, style = Hf.type.bodySm, color = Hf.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ChainDivider() {
    HorizontalDivider(color = Hf.colors.borderSubtle, thickness = 0.5.dp)
}

private fun iconFor(name: String): ImageVector = when (name) {
    "barbell" -> Icons.Filled.FitnessCenter
    "target" -> Icons.Outlined.TrackChanges
    "arrows-diff" -> Icons.Outlined.SwapVert
    "flame" -> Icons.Outlined.LocalFireDepartment
    "trending-down" -> Icons.AutoMirrored.Outlined.TrendingDown
    "scale" -> Icons.Outlined.Balance
    "pin" -> Icons.Outlined.PushPin
    else -> Icons.Outlined.Info
}

private fun macroLine(m: Macros?): String? {
    if (m == null) return null
    val parts = buildList {
        m.proteinGrams?.let { add("${it.roundToInt()}P") }
        m.carbsGrams?.let { add("${it.roundToInt()}C") }
        m.fatGrams?.let { add("${it.roundToInt()}F") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun describeBalance(balance: Double): String {
    val r = balance.roundToInt()
    if (kotlin.math.abs(r) < 75) return "roughly at maintenance"
    return if (r > 0) "+${fmt(r.toDouble())} surplus" else "${fmt(r.toDouble())} deficit"
}
