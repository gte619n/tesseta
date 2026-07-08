package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-STAB G3 — pick an earlier materialized session to log or review. The
 * backend only logs against an existing scheduled session, so this lists the
 * program's past sessions (date + label + status); tapping one opens the session
 * logger. A logged outcome can be deleted (reverted to planned). Shared by the
 * program detail and the "This Week" landing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastSessionsSheet(
    sessions: List<ScheduledWorkout>,
    onPick: (scheduledId: String) -> Unit,
    onDelete: (scheduledId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A logged day pending the delete (revert-to-planned) confirmation.
    var pendingDelete by remember { mutableStateOf<ScheduledWorkout?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Hf.colors.canvas) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Past workouts", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            if (sessions.isEmpty()) {
                Text(
                    "No earlier sessions yet.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            } else {
                sessions.forEach { session ->
                    // A logged outcome (completed or skipped) can be deleted —
                    // reverted to planned; a still-planned day has nothing to remove.
                    val isLogged = session.status == ScheduledStatus.COMPLETED ||
                        session.status == ScheduledStatus.SKIPPED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
                            .clickable { onPick(session.scheduledId) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                session.dayLabel,
                                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                                color = Hf.colors.textPrimary,
                            )
                            CapsLabel(
                                "${session.date} · ${session.status.name.lowercase()}",
                                color = Hf.colors.textTertiary,
                            )
                        }
                        if (isLogged) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete logged ${session.dayLabel}",
                                tint = Hf.colors.alert,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { pendingDelete = session }
                                    .padding(7.dp),
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowForward,
                            contentDescription = "Log ${session.dayLabel}",
                            tint = Hf.colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { session ->
        ConfirmDialog(
            title = "Delete this workout?",
            message = "The logged result for ${session.dayLabel} (${session.date}) will be removed " +
                "and the day goes back to planned. You can run it again later.",
            confirmLabel = "Delete",
            dismissLabel = "Cancel",
            destructive = true,
            onConfirm = {
                onDelete(session.scheduledId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
