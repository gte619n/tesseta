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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.feature.workouts.R
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** Which confirmation the parked-session banner is showing. */
private enum class ParkedPrompt { RESTORE, DISCARD }

/**
 * "Finished workout couldn't sync — restore to review" banner for a completion
 * upload the server terminally rejected (IMPL-17 A10/Q3). Shown beside the
 * resume banner on the workouts hub and the program detail. Tapping it
 * confirms (surfacing the orphaned-set count when the plan was rewritten
 * underneath the upload) and then [onRestore] re-materializes the draft; when
 * the scheduled session no longer exists locally the only recovery offered is
 * a destructive [onDiscard].
 */
@Composable
fun ParkedSessionBanner(
    parked: ParkedCompletion,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember(parked) { mutableStateOf<ParkedPrompt?>(null) }
    val open = {
        prompt = if (parked.sessionAvailable) ParkedPrompt.RESTORE else ParkedPrompt.DISCARD
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.alertBg, RoundedCornerShape(10.dp))
            .clickable { open() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = Hf.colors.alert,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.workout_session_parked_title),
                style = Hf.type.headingMd.copy(fontSize = 13.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            val meta = buildList {
                parked.dayLabel?.let { add(it) }
                add(
                    pluralStringResource(
                        R.plurals.workout_session_sets_logged,
                        parked.loggedSetCount,
                        parked.loggedSetCount,
                    ),
                )
                if (!parked.sessionAvailable) {
                    add(stringResource(R.string.workout_session_parked_gone))
                }
            }
            CapsLabel(meta.joinToString(" · "), color = Hf.colors.textTertiary)
        }
        Text(
            stringResource(
                if (parked.sessionAvailable) R.string.workout_session_parked_restore
                else R.string.workout_session_parked_discard,
            ),
            style = Hf.type.bodyMd,
            color = if (parked.sessionAvailable) Hf.colors.accent else Hf.colors.alert,
        )
    }

    when (prompt) {
        ParkedPrompt.RESTORE -> {
            val message = buildList {
                add(stringResource(R.string.workout_session_parked_restore_message))
                if (parked.orphanedSetCount > 0) {
                    add(
                        pluralStringResource(
                            R.plurals.workout_session_parked_orphans,
                            parked.orphanedSetCount,
                            parked.orphanedSetCount,
                        ),
                    )
                }
            }
            ConfirmDialog(
                title = stringResource(R.string.workout_session_parked_restore_title),
                message = message.joinToString(" "),
                confirmLabel = stringResource(R.string.workout_session_parked_restore),
                dismissLabel = stringResource(R.string.workout_session_cancel),
                onConfirm = {
                    prompt = null
                    onRestore()
                },
                onDismiss = { prompt = null },
            )
        }
        ParkedPrompt.DISCARD -> ConfirmDialog(
            title = stringResource(R.string.workout_session_parked_discard_title),
            message = stringResource(R.string.workout_session_parked_discard_message),
            confirmLabel = stringResource(R.string.workout_session_parked_discard),
            dismissLabel = stringResource(R.string.workout_session_cancel),
            destructive = true,
            onConfirm = {
                prompt = null
                onDiscard()
            },
            onDismiss = { prompt = null },
        )
        null -> Unit
    }
}
