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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

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

    // One-second ticker for the in-progress card's live elapsed — only runs when
    // a workout is actually in progress.
    var now by remember { mutableStateOf(Instant.now()) }
    val inProgress = state is TodayWorkout.Resume
    LaunchedEffect(inProgress) {
        if (!inProgress) return@LaunchedEffect
        while (true) {
            now = Instant.now()
            delay(1_000)
        }
    }

    // A finished session shows a richer recap card (volume/time/sets/calories)
    // rather than the single-row action card, so it branches out early.
    (state as? TodayWorkout.Completed)?.let { done ->
        Spacer(Modifier.height(11.dp))
        CompletedWorkoutCard(state = done, onNavigate = onNavigate, modifier = modifier)
        return
    }

    val model = when (val s = state) {
        TodayWorkout.Hidden -> null
        is TodayWorkout.Completed -> null // handled above
        is TodayWorkout.Resume -> {
            val elapsed = Duration.between(s.startedAt, now).toMillis().coerceAtLeast(0L) / 1000L
            CardModel(
                s.programId,
                s.scheduledId,
                "Resume workout",
                listOfNotNull(s.label, "In progress · ${elapsedClock(elapsed)}", setsLabel(s.setsLogged))
                    .joinToString(" · "),
            )
        }
        is TodayWorkout.Start -> CardModel(
            s.programId,
            s.scheduledId,
            if (s.isToday) "Start workout" else "Start next workout",
            s.label ?: if (s.isToday) "Today's session" else "Next session",
        )
    } ?: return
    val (programId, scheduledId, title, subtitle) = model

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

/**
 * Home-screen recap of the workout completed today: which session, total weight
 * moved (the hero), and time / sets / calorie-estimate stats. Taps into the
 * session review, matching the other dashboard cards' surface + border styling.
 */
@Composable
private fun CompletedWorkoutCard(
    state: TodayWorkout.Completed,
    onNavigate: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    HfCard(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onNavigate(WorkoutsRoutes.session(state.programId, state.scheduledId)) },
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Today's workout")
                Text(
                    text = "COMPLETED",
                    style = Hf.type.capsSm,
                    color = Hf.colors.accent,
                )
            }
            Spacer(Modifier.height(13.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.label ?: "Workout",
                        style = Hf.type.headingMd.copy(fontSize = 15.sp),
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = volumeValue(state.totalWeightLbs),
                            style = Hf.type.displayMd.copy(fontSize = 22.sp, lineHeight = 22.sp),
                            color = Hf.colors.textPrimary,
                        )
                        if (state.totalWeightLbs >= 1.0) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "lb moved",
                                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                                color = Hf.colors.textTertiary,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
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
            }
            Spacer(Modifier.height(13.dp))
            HRule()
            Spacer(Modifier.height(11.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecapStat(
                    label = "Time",
                    value = state.durationSeconds?.let { elapsedClock(it.toLong()) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
                RecapStat(
                    label = "Sets",
                    value = state.totalSets.toString(),
                    modifier = Modifier.weight(1f),
                )
                RecapStat(
                    label = "Cal",
                    value = state.estimatedCalories?.toString() ?: "—",
                    unit = state.estimatedCalories?.let { "est" },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RecapStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
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
            if (unit != null) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    style = Hf.type.bodySm.copy(fontSize = 10.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}

/** Group-separated volume ("12,340"); an em-dash when nothing loaded carried weight. */
private fun volumeValue(totalWeightLbs: Double): String =
    if (totalWeightLbs >= 1.0) "%,d".format(totalWeightLbs.roundToInt()) else "—"

private data class CardModel(
    val programId: String,
    val scheduledId: String,
    val title: String,
    val subtitle: String,
)

private fun setsLabel(n: Int): String = if (n == 1) "1 set logged" else "$n sets logged"

/** "MM:SS" / "H:MM:SS" elapsed for the in-progress card. */
private fun elapsedClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
