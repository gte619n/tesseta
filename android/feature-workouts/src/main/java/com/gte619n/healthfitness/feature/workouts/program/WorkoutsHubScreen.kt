package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.session.ui.ParkedSessionBanner
import com.gte619n.healthfitness.feature.workouts.session.ui.ResumeSessionBanner
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Workouts hub (IMPL-AND-15). The Workouts destination is a hub with two cards
 * — Gyms (IMPL-AND-06) and Programs (read-only). ADR-0012 adds a resume banner
 * when a local session draft is in flight (the only state the hub carries).
 */
@Composable
fun WorkoutsHubRoute(
    onBack: () -> Unit,
    onOpenGyms: () -> Unit,
    onOpenPrograms: () -> Unit,
    onResumeSession: (programId: String, scheduledId: String) -> Unit,
    onDesignProgram: () -> Unit = {},
    viewModel: WorkoutsHubViewModel = hiltViewModel(),
) {
    val activeDraft by viewModel.activeDraft.collectAsStateWithLifecycle()
    val parkedCompletion by viewModel.parkedCompletion.collectAsStateWithLifecycle()
    val parkedError by viewModel.parkedError.collectAsStateWithLifecycle()
    val restoredSession by viewModel.restoredSession.collectAsStateWithLifecycle()

    // A successful restore re-materialized the draft; drop into the logger.
    LaunchedEffect(restoredSession) {
        restoredSession?.let {
            viewModel.consumeRestoredSession()
            onResumeSession(it.programId, it.scheduledId)
        }
    }

    WorkoutsHubScreen(
        onBack = onBack,
        onOpenGyms = onOpenGyms,
        onOpenPrograms = onOpenPrograms,
        onDesignProgram = onDesignProgram,
        activeDraft = activeDraft,
        onResumeSession = { draft -> onResumeSession(draft.programId, draft.scheduledId) },
        parkedCompletion = parkedCompletion,
        parkedError = parkedError,
        onRestoreParked = viewModel::restoreParked,
        onDiscardParked = viewModel::discardParked,
    )
}

@Composable
fun WorkoutsHubScreen(
    onBack: () -> Unit,
    onOpenGyms: () -> Unit,
    onOpenPrograms: () -> Unit,
    onDesignProgram: () -> Unit = {},
    activeDraft: WorkoutSessionDraft? = null,
    onResumeSession: (WorkoutSessionDraft) -> Unit = {},
    parkedCompletion: ParkedCompletion? = null,
    parkedError: String? = null,
    onRestoreParked: (ParkedCompletion) -> Unit = {},
    onDiscardParked: (ParkedCompletion) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Workouts",
            subtitle = "Your gyms and training programs",
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (activeDraft != null) {
                ResumeSessionBanner(
                    draft = activeDraft,
                    onResume = { onResumeSession(activeDraft) },
                )
            }
            if (parkedCompletion != null) {
                ParkedSessionBanner(
                    parked = parkedCompletion,
                    onRestore = { onRestoreParked(parkedCompletion) },
                    onDiscard = { onDiscardParked(parkedCompletion) },
                )
            }
            if (parkedError != null) {
                Text(parkedError, style = Hf.type.bodySm, color = Hf.colors.alert)
            }
            HubCard(
                icon = Icons.Outlined.FitnessCenter,
                title = "Gyms",
                description = "Your gyms, equipment, and hours.",
                onClick = onOpenGyms,
            )
            HubCard(
                icon = Icons.Outlined.ListAlt,
                title = "Programs",
                description = "Your periodized training programs.",
                onClick = onOpenPrograms,
            )
            HubCard(
                icon = Icons.Outlined.AutoAwesome,
                title = "Design a program",
                description = "Plan a periodized program with the AI coach.",
                onClick = onDesignProgram,
            )
        }
    }
}

@Composable
private fun HubCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Hf.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = Hf.type.headingMd.copy(fontSize = 15.sp),
                    color = Hf.colors.textPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(description, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            }
            Text("›", style = Hf.type.headingLg, color = Hf.colors.textTertiary)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun WorkoutsHubPreview() {
    HealthFitnessTheme {
        WorkoutsHubScreen(onBack = {}, onOpenGyms = {}, onOpenPrograms = {})
    }
}
