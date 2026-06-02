package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.MuscleLabels
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * A read-only exercise detail sheet: a 3-step demo viewer (START/MID/END
 * stills via Coil with prev/next), the exercise name, primary-muscle chips,
 * and a bulleted form-cues list. Fed by the embedded [ExerciseSummary]; no
 * per-exercise fetch.
 */
@OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun ExerciseDetailSheet(
    summary: ExerciseSummary,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                summary.name,
                style = Hf.type.headingLg.copy(fontSize = 18.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(14.dp))

            DemoViewer(summary)

            if (summary.primaryMuscles.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                CapsLabel("Primary muscles", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.primaryMuscles.forEach { muscle ->
                        Chip(MuscleLabels.label(muscle))
                    }
                }
            }

            if (summary.formCues.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                CapsLabel("Form cues", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    summary.formCues.forEach { cue ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = Hf.type.bodyMd, color = Hf.colors.accent)
                            Text(cue, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoViewer(summary: ExerciseSummary) {
    val frames = summary.demoFrames.filter { it.imageUrl != null }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(Hf.colors.canvasMuted, RoundedCornerShape(12.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (frames.isEmpty()) {
            Text(
                "No demo available",
                style = Hf.type.bodyMd,
                color = Hf.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
            return@Box
        }

        var index by remember { mutableStateOf(0) }
        val frame = frames[index.coerceIn(0, frames.lastIndex)]

        HfAsyncImage(
            model = frame.imageUrl,
            contentDescription = "${summary.name} ${frame.phase}",
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
        )

        // Phase label, top-center.
        Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopCenter) {
            Box(
                modifier = Modifier
                    .background(Hf.colors.canvas.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                CapsLabel(frame.phase, color = Hf.colors.textSecondary)
            }
        }

        if (frames.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepArrow(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    description = "Previous frame",
                ) { index = (index - 1 + frames.size) % frames.size }
                StepArrow(
                    icon = Icons.AutoMirrored.Outlined.ArrowForward,
                    description = "Next frame",
                ) { index = (index + 1) % frames.size }
            }
        }
    }
}

@Composable
private fun StepArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(Hf.colors.canvas.copy(alpha = 0.85f), RoundedCornerShape(17.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = Hf.colors.textPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun Chip(text: String) {
    Box(
        modifier = Modifier
            .background(Hf.colors.accentBg, RoundedCornerShape(6.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(6.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(text, style = Hf.type.bodySm, color = Hf.colors.accentDim)
    }
}
