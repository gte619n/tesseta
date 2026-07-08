package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * One phase in the program roadmap: a spine node + expandable header (title,
 * meta, status pill, chevron) that reveals the phase's workout-day rows. Shared
 * by the program detail and the "This Week" landing roadmap.
 *
 * [defaultExpanded] decides which phases open on first composition. The landing
 * opens only the ACTIVE phase so the roadmap reads cleanly top-to-bottom
 * (completed collapsed → active open → upcoming collapsed); the program detail
 * passes its legacy rule (first phase OR active) for parity with its old view.
 */
@Composable
fun PhaseRow(
    phase: ProgramPhase,
    isFirst: Boolean,
    isLast: Boolean,
    onOpenWorkout: (phaseId: String, dayId: String) -> Unit,
    defaultExpanded: (ProgramPhase) -> Boolean = { it.status == ProgramPhaseStatus.ACTIVE },
) {
    var expanded by remember(phase.phaseId) { mutableStateOf(defaultExpanded(phase)) }
    Row(modifier = Modifier.fillMaxWidth()) {
        PhaseSpineNode(status = phase.status, isFirst = isFirst, isLast = isLast)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 18.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        phase.title,
                        style = Hf.type.headingMd.copy(fontSize = 14.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(5.dp))
                    PhaseMeta(phase)
                }
                Spacer(Modifier.width(8.dp))
                PhaseStatusPill(phase.status)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse phase" else "Expand phase",
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val days = phase.days.sortedBy { it.orderIndex }
                    if (days.isEmpty()) {
                        Text(
                            "No workouts in this phase yet.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    } else {
                        days.forEach { day ->
                            WorkoutDayRow(
                                day = day,
                                onOpen = { onOpenWorkout(phase.phaseId, day.dayId) },
                            )
                        }
                    }
                }
            }
        }
    }
}
