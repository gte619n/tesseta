package com.gte619n.healthfitness.feature.workouts.session

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.gte619n.healthfitness.domain.workout.Exercise
import com.gte619n.healthfitness.domain.workout.PlayerStep
import com.gte619n.healthfitness.domain.workout.WeightUnit

private val Ink = Color(0xFF0C0D0B)
private val Paper = Color(0xFFF4F2EC)
private val Dim = Color(0xFF9A9B94)
private val Accent = Color(0xFF8FB14C)
private val PanelBg = Color(0xFF1C1E1A)

@Composable
fun WorkoutPlayerRoute(
    sessionId: String,
    onFinished: (String) -> Unit,
    onExit: () -> Unit,
    viewModel: WorkoutPlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(sessionId) { viewModel.load(sessionId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finishedSessionId) {
        state.finishedSessionId?.let(onFinished)
    }

    WorkoutPlayerScreen(
        state = state,
        onLogSet = viewModel::logCurrentSet,
        onPauseToggle = viewModel::togglePause,
        onSkipRest = viewModel::skipRest,
        onAddRest = { viewModel.addRestTime(15) },
        onExit = onExit,
    )
}

@Composable
fun WorkoutPlayerScreen(
    state: WorkoutPlayerUiState,
    onLogSet: (Int?, Double?) -> Unit,
    onPauseToggle: () -> Unit,
    onSkipRest: () -> Unit,
    onAddRest: () -> Unit,
    onExit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Ink)) {
        ExerciseVideo(
            exercise = state.currentExercise,
            modifier = Modifier.fillMaxSize(),
        )
        // Legibility scrim.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Ink.copy(alpha = 0.55f),
                        0.4f to Color.Transparent,
                        0.65f to Color.Transparent,
                        1f to Ink.copy(alpha = 0.85f),
                    ),
                ),
        )

        // On phones the content fills the width; on a wide/unfolded display we
        // cap it to a readable column and center it so controls don't stretch.
        Box(modifier = Modifier.fillMaxSize().systemBarsPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 560.dp),
            ) {
                TopBar(state = state, onExit = onExit, onPauseToggle = onPauseToggle)
                Spacer(Modifier.weight(1f))

                when {
                    state.loading || state.phase == PlayerPhase.Loading ->
                        CenterSpinner()
                    state.phase == PlayerPhase.Finishing ->
                        CenterSpinner(label = "Saving your workout…")
                    state.currentStep is PlayerStep.Rest ->
                        RestPanel(
                            step = state.currentStep as PlayerStep.Rest,
                            secondsRemaining = state.secondsRemaining ?: 0,
                            onSkip = onSkipRest,
                            onAdd = onAddRest,
                        )
                    state.currentStep is PlayerStep.PerformSet ->
                        PerformPanel(
                            step = state.currentStep as PlayerStep.PerformSet,
                            exercise = state.currentExercise,
                            paused = state.phase == PlayerPhase.Paused,
                            secondsRemaining = state.secondsRemaining,
                            onLogSet = onLogSet,
                            onPauseToggle = onPauseToggle,
                        )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExerciseVideo(exercise: Exercise?, modifier: Modifier = Modifier) {
    val videoUrl = exercise?.demoVideoUrl
    if (videoUrl != null) {
        val context = LocalContext.current
        val player = remember {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }
        }
        LaunchedEffect(videoUrl) {
            player.setMediaItem(MediaItem.fromUri(videoUrl))
            player.prepare()
        }
        DisposableEffect(Unit) {
            onDispose { player.release() }
        }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
        )
    } else if (exercise?.demoImageUrl != null) {
        AsyncImage(
            model = exercise.demoImageUrl,
            contentDescription = exercise.name,
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.background(Ink))
    }
}

@Composable
private fun TopBar(state: WorkoutPlayerUiState, onExit: () -> Unit, onPauseToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(Icons.Filled.Close, contentDescription = "Exit", tint = Paper)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.title, color = Paper, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            Text(
                "${state.setsCompleted}/${state.totalSets} sets · ${trimVolume(state.runningVolume)} lb",
                color = Dim,
                fontSize = 11.sp,
            )
        }
        IconButton(onClick = onPauseToggle) {
            val paused = state.phase == PlayerPhase.Paused
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = if (paused) "Resume" else "Pause",
                tint = Paper,
            )
        }
    }
}

@Composable
private fun PerformPanel(
    step: PlayerStep.PerformSet,
    exercise: Exercise?,
    paused: Boolean,
    secondsRemaining: Int?,
    onLogSet: (Int?, Double?) -> Unit,
    onPauseToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            step.exercise.name,
            color = Paper,
            style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text("Set ${step.setOrdinal} of ${step.setCount}", color = Accent, fontSize = 13.sp)

        val cue = exercise?.cues?.firstOrNull() ?: step.exercise.notes
        if (!cue.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(cue, color = Dim, fontSize = 13.sp)
        }

        Spacer(Modifier.height(18.dp))

        if (step.set.isTimed) {
            TimedControls(secondsRemaining ?: (step.set.targetSeconds ?: 0), paused, onPauseToggle)
        } else {
            SetLogger(step, onLogSet)
        }
    }
}

@Composable
private fun TimedControls(seconds: Int, paused: Boolean, onPauseToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(formatClock(seconds), color = Paper, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(if (paused) "Resume" else "Pause", onPauseToggle)
    }
}

@Composable
private fun SetLogger(step: PlayerStep.PerformSet, onLogSet: (Int?, Double?) -> Unit) {
    val unit = step.set.weightUnit
    val increment = if (unit == WeightUnit.KG) 2.5 else 5.0
    var reps by remember(step.set.setId) { mutableStateOf(step.set.targetReps ?: 0) }
    var weight by remember(step.set.setId) { mutableStateOf(step.set.targetWeight) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Stepper(
            label = "REPS",
            value = reps.toString(),
            onMinus = { if (reps > 0) reps-- },
            onPlus = { reps++ },
            modifier = Modifier.weight(1f),
        )
        Stepper(
            label = "WEIGHT (${unit.name})",
            value = weight?.let { trimVolume(it) } ?: "BW",
            onMinus = { weight = ((weight ?: 0.0) - increment).coerceAtLeast(0.0) },
            onPlus = { weight = (weight ?: 0.0) + increment },
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton("Log set", onClick = { onLogSet(reps.takeIf { it > 0 }, weight) })
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Dim, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Filled.Remove, "Decrease", onMinus)
            Text(
                value,
                color = Paper,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            RoundIcon(Icons.Filled.Add, "Increase", onPlus)
        }
    }
}

@Composable
private fun RestPanel(
    step: PlayerStep.Rest,
    secondsRemaining: Int,
    onSkip: () -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("REST", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(formatClock(secondsRemaining), color = Paper, fontSize = 60.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Up next · ${step.upNext.exercise.name} · Set ${step.upNext.setOrdinal}",
            color = Dim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("+15s", onAdd)
            PrimaryButton("Skip rest", onSkip)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PanelBg, contentColor = Paper),
    ) {
        Text(text)
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(PanelBg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = desc, tint = Paper)
        }
    }
}

@Composable
private fun CenterSpinner(label: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Accent)
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, color = Dim)
        }
    }
}

private fun formatClock(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun trimVolume(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
