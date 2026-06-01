package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.feature.workouts.program.trainingDaysSummary
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// ---- Status display (all rendered from backend state; no client computation) ----

@Composable
fun ProgramStatusPill(status: ProgramStatus) {
    when (status) {
        ProgramStatus.ACTIVE -> Pill("Active", HfTone.Good)
        ProgramStatus.COMPLETED -> Pill("Completed", HfTone.Good)
        ProgramStatus.DRAFT -> Pill("Draft", HfTone.Neutral)
        ProgramStatus.ARCHIVED -> Pill("Archived", HfTone.Neutral)
    }
}

@Composable
fun PhaseStatusPill(status: ProgramPhaseStatus) {
    when (status) {
        ProgramPhaseStatus.COMPLETED -> Pill("Completed", HfTone.Good)
        ProgramPhaseStatus.ACTIVE -> Pill("Active", HfTone.Good)
        ProgramPhaseStatus.LOCKED -> Pill("Locked", HfTone.Neutral)
    }
}

@Composable
fun DeloadBadge() {
    Pill("Deload", HfTone.Warn)
}

// ---- Program card (list) ----

/**
 * Compact list card: title, status pill, a phase spine (segment per phase,
 * colored by status, deload weeks marked), "Phase N of M", total weeks, and
 * the training-day summary. All status comes from the backend.
 */
@Composable
fun ProgramCard(program: WorkoutProgram, onClick: () -> Unit) {
    val (completed, total) = program.phaseProgress
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgramStatusPill(program.status)
                if (program.totalWeeks > 0) {
                    CapsLabel("${program.totalWeeks} weeks", color = Hf.colors.textTertiary)
                }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                program.title,
                style = Hf.type.headingMd.copy(fontSize = 15.sp),
                color = Hf.colors.textPrimary,
            )
            if (!program.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    program.description!!,
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(11.dp))
            PhaseSpine(program = program)
            Spacer(Modifier.height(9.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsLabel("Phase $completed of $total", color = Hf.colors.textSecondary)
                CapsLabel(
                    trainingDaysSummary(program.trainingDays),
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}

/**
 * Phase spine: one weighted segment per phase. On the shallow list (no
 * per-phase status) it tints by program status and uses [WorkoutProgram.phaseCount];
 * on the deep program it colors each segment by its true phase status and marks
 * phases that contain a deload week.
 */
@Composable
private fun PhaseSpine(program: WorkoutProgram) {
    val phases = program.phases.sortedBy { it.orderIndex }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (phases.isNotEmpty()) {
            phases.forEach { phase ->
                PhaseSegment(
                    color = segmentColor(phase.status),
                    deload = phase.deloadWeekIndex != null,
                )
            }
        } else {
            val color = shallowSegmentColor(program.status)
            repeat(program.phaseCount.coerceAtLeast(1)) {
                PhaseSegment(color = color, deload = false)
            }
        }
    }
}

@Composable
private fun RowScope.PhaseSegment(color: Color, deload: Boolean) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(6.dp)
            .background(color, RoundedCornerShape(3.dp))
            .border(0.5.dp, Hf.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (deload) {
            // Deload mark: a small warn dot centered on the segment.
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Hf.colors.warn, CircleShape),
            )
        }
    }
}

private fun segmentColor(status: ProgramPhaseStatus): Color = when (status) {
    ProgramPhaseStatus.COMPLETED -> Color(0xFF5C7A2E) // accent
    ProgramPhaseStatus.ACTIVE -> Color(0xFF8A9F5C) // goodAlt
    ProgramPhaseStatus.LOCKED -> Color(0xFFB5B09C) // muted
}

private fun shallowSegmentColor(status: ProgramStatus): Color = when (status) {
    ProgramStatus.COMPLETED -> Color(0xFF5C7A2E)
    ProgramStatus.ACTIVE -> Color(0xFFE8EBD8) // accentBg
    ProgramStatus.DRAFT -> Color(0xFFE8EBD8)
    ProgramStatus.ARCHIVED -> Color(0xFFB5B09C)
}

// ---- Phase timeline spine node (reuses the Goals roadmap idiom) ----

@Composable
fun PhaseSpineNode(
    status: ProgramPhaseStatus,
    isFirst: Boolean,
    isLast: Boolean,
) {
    val nodeColor: Color = when (status) {
        ProgramPhaseStatus.COMPLETED -> Hf.colors.accent
        ProgramPhaseStatus.ACTIVE -> Hf.colors.accent
        ProgramPhaseStatus.LOCKED -> Hf.colors.muted
    }
    val lineAbove =
        if (status == ProgramPhaseStatus.LOCKED) Hf.colors.borderDefault else Hf.colors.accent
    val lineBelow =
        if (status == ProgramPhaseStatus.COMPLETED) Hf.colors.accent else Hf.colors.borderDefault

    Column(
        modifier = Modifier.width(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(8.dp)
                .background(if (isFirst) Color.Transparent else lineAbove),
        )
        if (status == ProgramPhaseStatus.ACTIVE) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(Hf.colors.surface, CircleShape)
                    .border(2.dp, nodeColor, CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(nodeColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when (status) {
                    ProgramPhaseStatus.COMPLETED -> Icon(
                        Icons.Outlined.Check,
                        contentDescription = null,
                        tint = Hf.colors.textInverse,
                        modifier = Modifier.size(8.dp),
                    )
                    ProgramPhaseStatus.LOCKED -> Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = Hf.colors.surface,
                        modifier = Modifier.size(7.dp),
                    )
                    else -> {}
                }
            }
        }
        if (!isLast) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .weight(1f)
                    .background(lineBelow),
            )
        }
    }
}

@Composable
fun PhaseMeta(phase: ProgramPhase) {
    val pieces = buildList {
        add("${phase.weeks} weeks")
        phase.focus?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CapsLabel(pieces.joinToString(" · "), color = Hf.colors.textTertiary)
        if (phase.deloadWeekIndex != null) {
            DeloadBadge()
        }
    }
}
