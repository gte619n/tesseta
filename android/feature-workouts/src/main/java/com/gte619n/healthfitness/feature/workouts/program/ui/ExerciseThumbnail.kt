package com.gte619n.healthfitness.feature.workouts.program.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * A small rounded thumbnail of an exercise demo image, used to give workout
 * rows (history, this-week) a visual anchor. Falls back to a dumbbell glyph when
 * the exercise has no usable frame.
 */
@Composable
fun ExerciseThumbnail(
    imageUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.canvasMuted),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            HfAsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.FitnessCenter,
                contentDescription = null,
                tint = Hf.colors.textQuaternary,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/** First usable demo image across a day's blocks (in plan order), or null. */
fun firstExerciseImageUrl(day: WorkoutDay?): String? =
    day?.blocks
        ?.sortedBy { it.orderIndex }
        ?.firstNotNullOfOrNull { block ->
            block.prescriptions
                .sortedBy { it.orderIndex }
                .firstNotNullOfOrNull { rx ->
                    rx.exercise?.demoFrames
                        ?.sortedBy { it.order }
                        ?.firstOrNull { it.imageUrl != null }
                        ?.imageUrl
                }
        }

/** Convenience for a scheduled session's snapshot day. */
fun firstExerciseImageUrl(session: ScheduledWorkout): String? =
    firstExerciseImageUrl(session.session)
