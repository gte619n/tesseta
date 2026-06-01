package com.gte619n.healthfitness.feature.blood.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.feature.blood.UploadLabReportViewModel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

private enum class Phase(val label: String) {
    UPLOADING("Uploading"),
    EXTRACTING("Extracting"),
    SAVING("Saving"),
}

/**
 * Three-state stepper for the lab-PDF upload flow: Uploading → Extracting →
 * Saving. The active phase is highlighted with [Hf.colors.accent]; completed
 * phases use [Hf.colors.good].
 */
@Composable
fun UploadPhaseStepper(
    state: UploadLabReportViewModel.UiState,
    modifier: Modifier = Modifier,
) {
    val activeIndex = when (state) {
        UploadLabReportViewModel.UiState.Uploading -> 0
        UploadLabReportViewModel.UiState.Extracting -> 1
        UploadLabReportViewModel.UiState.Saving -> 2
        is UploadLabReportViewModel.UiState.Complete -> 3 // all done
        else -> -1
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Phase.entries.forEachIndexed { i, phase ->
            val done = i < activeIndex
            val active = i == activeIndex
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                val dotColor = when {
                    done -> Hf.colors.good
                    active -> Hf.colors.accent
                    else -> Hf.colors.borderDefault
                }
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = phase.label,
                    style = Hf.type.bodyMd,
                    color = if (active || done) Hf.colors.textPrimary else Hf.colors.textTertiary,
                )
            }
        }
    }
}
