package com.gte619n.healthfitness.feature.workouts.session.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.R
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * "Workout in progress" banner for an in-flight local session draft
 * (ADR-0012 / IMPL-AND-17). Shown wherever the user would look for it — the
 * workouts hub and the program detail — and tapping it resumes the logger.
 */
@Composable
fun ResumeSessionBanner(
    draft: WorkoutSessionDraft,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.accentBg, RoundedCornerShape(10.dp))
            .clickable { onResume() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.PlayCircle,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.workout_session_in_progress),
                style = Hf.type.headingMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildList {
                draft.scheduled.dayLabel.takeIf { it.isNotBlank() }?.let { add(it) }
                add(
                    pluralStringResource(
                        R.plurals.workout_session_sets_logged,
                        draft.totalLoggedSets,
                        draft.totalLoggedSets,
                    ),
                )
            }
            CapsLabel(meta.joinToString(" · "), color = Hf.colors.textTertiary)
        }
        Text(
            stringResource(R.string.workout_session_resume),
            style = Hf.type.bodyMd,
            color = Hf.colors.accent,
        )
    }
}
