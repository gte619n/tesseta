package com.gte619n.healthfitness.feature.medical.today

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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Today's Doses card for embedding on the dashboard. Lives in the feature
 * module (not app/) so the dashboard can compose it directly. Sourced from
 * `GET /api/me/medications/today`; checkbox toggles are optimistic with
 * snackbar-revert on failure (handled in [TodaysDosesViewModel]).
 */
@Composable
fun TodaysDosesCard(
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodaysDosesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TodaysDosesCardContent(
        state = state,
        onToggle = viewModel::toggle,
        onSeeAll = onSeeAll,
        modifier = modifier,
    )
}

@Composable
fun TodaysDosesCardContent(
    state: TodaysDosesUiState,
    onToggle: (TodaysDose) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Today's doses")
                Text(
                    "View all",
                    style = Hf.type.capsSm,
                    color = Hf.colors.accent,
                    modifier = Modifier.clickable { onSeeAll() },
                )
            }
            Spacer(Modifier.height(12.dp))

            when (state) {
                is TodaysDosesUiState.Loading ->
                    Text("Loading…", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                is TodaysDosesUiState.Error ->
                    Text(state.message, style = Hf.type.bodySm, color = Hf.colors.alert)
                is TodaysDosesUiState.Ready ->
                    if (state.doses.isEmpty()) {
                        Text(
                            "No scheduled doses for today.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.doses.take(3).forEach { dose ->
                                DoseRow(dose = dose, onToggle = { onToggle(dose) })
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun DoseRow(dose: TodaysDose, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (dose.taken) Hf.colors.accent else Hf.colors.surface,
                    RoundedCornerShape(6.dp),
                )
                .border(
                    0.5.dp,
                    if (dose.taken) Hf.colors.accent else Hf.colors.borderStrong,
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (dose.taken) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = "Taken",
                    tint = Hf.colors.textInverse,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Column {
            Text(dose.drugName, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Text(
                "${DoseFormatter.format(dose.dose, dose.unit)} · ${TimeWindowLabels.label(dose.window)}",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}
