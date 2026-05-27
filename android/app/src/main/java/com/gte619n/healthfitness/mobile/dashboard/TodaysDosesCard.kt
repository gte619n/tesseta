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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.ui.snackbar.LocalSnackbarController
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Interactive Today's Doses section for the dashboard. Replaces the
 * read-only `DosesPreview` from IMPL-AND-01. Each row is a tap target —
 * tapping flips the checkbox optimistically and fires the backend POST
 * in the background.
 *
 * Surfaces errors via the app-wide [LocalSnackbarController]; reverts
 * the flip by re-fetching truth from the server.
 */
@Composable
fun TodaysDosesSection(
    onSeeAll: () -> Unit,
    viewModel: TodaysDosesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.errors.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarController.current

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbar.show(message, isError = true)
        viewModel.clearError()
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "DOSES TODAY",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
            Text(
                text = "View all",
                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                color = Hf.colors.accent,
                modifier = Modifier.clickable(onClick = onSeeAll),
            )
        }
        Spacer(Modifier.height(7.dp))
        when (val s = state) {
            TodaysDosesUiState.Loading -> Text(
                "Loading...",
                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
            is TodaysDosesUiState.Error -> Text(
                s.message,
                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                color = Hf.colors.alert,
            )
            is TodaysDosesUiState.Ready -> {
                if (s.doses.isEmpty()) {
                    Text(
                        text = "No scheduled doses for today.",
                        style = Hf.type.bodySm.copy(fontSize = 11.sp),
                        color = Hf.colors.textTertiary,
                    )
                } else {
                    s.doses.forEachIndexed { i, dose ->
                        InteractiveDoseRow(
                            dose = dose,
                            onToggle = { viewModel.toggle(dose) },
                        )
                        if (i != s.doses.lastIndex) Spacer(Modifier.height(7.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveDoseRow(
    dose: TodaysDose,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TakenCheckbox(taken = dose.taken)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dose.drugName,
                style = Hf.type.bodyMd.copy(fontSize = 12.sp),
                color = Hf.colors.textPrimary,
            )
            Text(
                text = dose.unit?.let { DoseFormatter.format(dose.dose, it) }
                    ?: DoseFormatter.formatDoseOnly(dose.dose),
                style = Hf.type.monoSm,
                color = Hf.colors.textTertiary,
            )
        }
        Text(
            text = dose.window.shortCaps(),
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
    }
}

@Composable
private fun TakenCheckbox(taken: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (taken) Hf.colors.accent else Hf.colors.surface)
            .border(
                0.75.dp,
                if (taken) Hf.colors.accent else Hf.colors.borderStrong,
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (taken) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(10.dp),
            )
        }
    }
}
