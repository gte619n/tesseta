package com.gte619n.healthfitness.feature.medical.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    // Re-fetch on resume so dose/schedule edits made on the medication detail
    // screen show up when the user returns to the dashboard. The VM only loads
    // once in init, and its instance survives in the back stack, so without
    // this the card keeps showing the pre-edit schedule.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
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
    val doses = (state as? TodaysDosesUiState.Ready)?.doses.orEmpty()
    val allTaken = doses.isNotEmpty() && doses.all { it.taken }

    // Once every dose is checked off the card collapses to a compact
    // "Complete" header; the chevron expands it back to the full list.
    // Re-completing the list (un-check then re-check) re-collapses it.
    var expanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(allTaken) { if (allTaken) expanded = false }
    val showDoses = !allTaken || expanded

    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionTitle("Today's doses")
                    if (allTaken) CompleteBadge()
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "View all",
                        style = Hf.type.capsSm,
                        color = Hf.colors.accent,
                        modifier = Modifier.clickable { onSeeAll() },
                    )
                    if (allTaken) {
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (expanded) "Collapse doses" else "Expand doses",
                            tint = Hf.colors.textTertiary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { expanded = !expanded },
                        )
                    }
                }
            }

            when (state) {
                is TodaysDosesUiState.Loading -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Loading…", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
                is TodaysDosesUiState.Error -> {
                    Spacer(Modifier.height(12.dp))
                    Text(state.message, style = Hf.type.bodySm, color = Hf.colors.alert)
                }
                is TodaysDosesUiState.Ready ->
                    if (state.doses.isEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No scheduled doses for today.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    } else {
                        // Roll the list up when the day is complete (and back
                        // down when re-expanded or a dose is unchecked) rather
                        // than popping it out instantly. Anchored at the top so
                        // the card's bottom edge glides up under the header; the
                        // leading Spacer lives inside the animated content so the
                        // gap collapses with the rows. The dashboard hosting this
                        // card is itself vertically scrollable, so a long list
                        // stays reachable by scrolling (no inner scroll — that
                        // would conflict with the parent's vertical scroll).
                        AnimatedVisibility(
                            visible = showDoses,
                            enter = expandVertically(
                                animationSpec = tween(ROLL_MS),
                                expandFrom = Alignment.Top,
                            ) + fadeIn(animationSpec = tween(ROLL_MS)),
                            exit = shrinkVertically(
                                animationSpec = tween(ROLL_MS),
                                shrinkTowards = Alignment.Top,
                            ) + fadeOut(animationSpec = tween(ROLL_MS)),
                        ) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.doses.forEach { dose ->
                                        DoseRow(dose = dose, onToggle = { onToggle(dose) })
                                    }
                                }
                            }
                        }
                    }
                // Collapsed + complete: header-only, the list above is rolled up.
            }
        }
    }
}

@Composable
private fun CompleteBadge() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(14.dp),
        )
        Text("Complete", style = Hf.type.capsSm, color = Hf.colors.accent)
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

// Duration of the dose-list roll up/down. Long enough to read as a smooth
// collapse, short enough to stay snappy when checking off the last dose.
private const val ROLL_MS = 250
