package com.gte619n.healthfitness.feature.workouts.program.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockTypeLabels
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.feature.workouts.program.dayOfWeekLabel
import com.gte619n.healthfitness.feature.workouts.program.exerciseCountLabel
import com.gte619n.healthfitness.feature.workouts.program.loggedSetsSummary
import com.gte619n.healthfitness.feature.workouts.program.prescriptionSummary
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * A workout-day summary row: label, caps-mono day-of-week + gym name, and an
 * exercise count. Tapping it opens the dedicated workout detail screen (rows on
 * phone, exercise tiles on tablet).
 */
@Composable
fun WorkoutDayRow(
    day: WorkoutDay,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                day.label.ifBlank { dayOfWeekLabel(day.dayOfWeek) },
                style = Hf.type.headingMd.copy(fontSize = 14.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(3.dp))
            val meta = buildList {
                add(dayOfWeekLabel(day.dayOfWeek))
                day.locationName?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(exerciseCountLabel(day))
            }
            CapsLabel(meta.joinToString(" · "), color = Hf.colors.textTertiary)
        }
        Icon(
            Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = "Open workout",
            tint = Hf.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** A typed block (warm-up, main, cardio, …) with its ordered prescriptions. */
@Composable
fun BlockSection(
    block: Block,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(text = BlockTypeLabels.label(block.type), compact = true)
        block.prescriptions.sortedBy { it.orderIndex }.forEach { prescription ->
            PrescriptionRow(prescription = prescription, onOpenExercise = onOpenExercise)
        }
    }
}

/**
 * A single prescription row: a small START-frame demo thumbnail, the exercise
 * name, a compact "3 × 8–10 @ RPE 8 · rest 90s" prescription line, the logged
 * sets (weights) when the session was performed, optional notes, and a deload
 * chip when a modifier is present. Tapping opens the exercise detail sheet.
 */
@Composable
fun PrescriptionRow(
    prescription: Prescription,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    val exercise = prescription.exercise
    val startFrame = exercise?.demoFrames?.firstOrNull { it.imageUrl != null }?.imageUrl
    val clickable = exercise != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (clickable) Modifier.clickable { onOpenExercise(exercise!!) } else Modifier,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Hf.colors.canvasMuted),
        ) {
            if (startFrame != null) {
                HfAsyncImage(
                    model = startFrame,
                    contentDescription = exercise?.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                exercise?.name ?: prescription.exerciseId,
                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            val summary = prescriptionSummary(prescription)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, style = Hf.type.monoSm, color = Hf.colors.textSecondary)
            }
            loggedSetsSummary(prescription)?.let { logged ->
                Spacer(Modifier.height(2.dp))
                Text(logged, style = Hf.type.monoSm, color = Hf.colors.accentDim)
            }
            if (!prescription.notes.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    prescription.notes!!,
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            }
        }
        if (prescription.deloadModifier != null) {
            DeloadBadge()
        }
    }
}

/**
 * A tile rendering of a single prescription, for the tablet workout-detail grid
 * (≈3 per row). Shows a wide demo frame, the exercise name, the prescription
 * line, logged weights when present, and a deload chip. Tapping opens the
 * exercise detail sheet.
 */
@Composable
fun ExerciseTile(
    prescription: Prescription,
    onOpenExercise: (ExerciseSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val exercise = prescription.exercise
    val startFrame = exercise?.demoFrames?.firstOrNull { it.imageUrl != null }?.imageUrl
    val clickable = exercise != null
    Column(
        modifier = modifier
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(12.dp))
            .background(Hf.colors.surface, RoundedCornerShape(12.dp))
            .then(
                if (clickable) Modifier.clickable { onOpenExercise(exercise!!) } else Modifier,
            )
            .padding(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(104.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Hf.colors.canvasMuted),
        ) {
            if (startFrame != null) {
                HfAsyncImage(
                    model = startFrame,
                    contentDescription = exercise?.name,
                    modifier = Modifier.fillMaxWidth().height(104.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            exercise?.name ?: prescription.exerciseId,
            style = Hf.type.headingMd.copy(fontSize = 13.sp),
            color = Hf.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val summary = prescriptionSummary(prescription)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(summary, style = Hf.type.monoSm, color = Hf.colors.textSecondary)
        }
        loggedSetsSummary(prescription)?.let { logged ->
            Spacer(Modifier.height(2.dp))
            Text(logged, style = Hf.type.monoSm, color = Hf.colors.accentDim)
        }
        if (!prescription.notes.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                prescription.notes!!,
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (prescription.deloadModifier != null) {
            Spacer(Modifier.height(6.dp))
            DeloadBadge()
        }
    }
}
