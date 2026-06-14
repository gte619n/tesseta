package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.feature.workouts.R
import com.gte619n.healthfitness.feature.workouts.program.scheduledDateLabel
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

/**
 * Horizontal strip of the current week's scheduled sessions: date, day label,
 * gym, a deload tint, and a status dot. `isDeload` / status come from the
 * backend ScheduledWorkout (authoritative — the client never recomputes).
 *
 * ADR-0012 (IMPL-AND-17): today's still-PLANNED session gets a "Start workout"
 * affordance ([today] + [onStartSession]), suppressed via [canStart] while a
 * local draft is already in flight.
 */
@Composable
fun ThisWeekStrip(
    scheduled: List<ScheduledWorkout>,
    today: LocalDate? = null,
    canStart: Boolean = true,
    onStartSession: ((ScheduledWorkout) -> Unit)? = null,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(scheduled, key = { it.scheduledId }) { session ->
            val startable = canStart &&
                onStartSession != null &&
                session.status == ScheduledStatus.PLANNED &&
                session.date == today
            ScheduledCard(
                session = session,
                onStart = if (startable) {
                    { onStartSession?.invoke(session) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun ScheduledCard(session: ScheduledWorkout, onStart: (() -> Unit)?) {
    val bg = if (session.isDeload) Hf.colors.warnBg else Hf.colors.surface
    Column(
        modifier = Modifier
            .width(150.dp)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CapsLabel(scheduledDateLabel(session.date), color = Hf.colors.textSecondary)
            StatusDot(session.status)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            session.dayLabel.ifBlank { "Session" },
            style = Hf.type.headingMd.copy(fontSize = 13.sp),
            color = Hf.colors.textPrimary,
        )
        session.locationName?.takeIf { it.isNotBlank() }?.let { gym ->
            Spacer(Modifier.height(3.dp))
            CapsLabel(gym, color = Hf.colors.textTertiary)
        }
        if (session.isDeload) {
            Spacer(Modifier.height(6.dp))
            DeloadBadge()
        }
        if (onStart != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Hf.colors.accent, RoundedCornerShape(6.dp))
                    .clickable { onStart() }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.workout_session_start).uppercase(),
                    style = Hf.type.capsMd,
                    color = Hf.colors.textInverse,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: ScheduledStatus) {
    val color: Color = when (status) {
        ScheduledStatus.COMPLETED -> Hf.colors.accent
        ScheduledStatus.PLANNED -> Hf.colors.muted
        ScheduledStatus.SKIPPED -> Hf.colors.alert
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}
