package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * The home screen's one-tap entry into the workout coach — coaching is the
 * primary surface (the user starts/resumes here, not five taps deep in the hub).
 * Resumes an in-progress draft when one exists, otherwise starts today's planned
 * session; renders nothing on a rest day. Both land directly on the session
 * route ([WorkoutsRoutes.session]).
 */
@Composable
fun TodayWorkoutCard(
    onNavigate: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: TodayWorkoutViewModel = hiltViewModel()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val state by vm.state.collectAsStateWithLifecycle()

    val (programId, scheduledId, title, subtitle) = when (val s = state) {
        TodayWorkout.Hidden -> return
        is TodayWorkout.Resume -> CardModel(
            s.programId,
            s.scheduledId,
            "Resume workout",
            listOfNotNull(s.label, setsLabel(s.setsLogged)).joinToString(" · "),
        )
        is TodayWorkout.Start -> CardModel(
            s.programId,
            s.scheduledId,
            if (s.isToday) "Start workout" else "Start next workout",
            s.label ?: if (s.isToday) "Today's session" else "Next session",
        )
    }

    Spacer(Modifier.height(11.dp))
    Row(
        modifier = modifier
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .clickable { onNavigate(WorkoutsRoutes.session(programId, scheduledId)) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Hf.colors.accentBg, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DashboardIcons.Barbell,
                contentDescription = null,
                tint = Hf.colors.accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = Hf.type.headingMd.copy(fontSize = 15.sp),
                color = Hf.colors.textPrimary,
            )
            Text(
                text = subtitle,
                style = Hf.type.monoSm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Text(
            text = "›",
            style = Hf.type.headingLg,
            color = Hf.colors.accent,
        )
    }
}

private data class CardModel(
    val programId: String,
    val scheduledId: String,
    val title: String,
    val subtitle: String,
)

private fun setsLabel(n: Int): String = if (n == 1) "1 set logged" else "$n sets logged"
