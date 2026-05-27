package com.gte619n.healthfitness.mobile.dashboard.viewmodel

import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary

/**
 * Aggregate dashboard state — three independent per-card sub-states.
 * The Compose layer reads this and switches on each card individually.
 *
 *  - [bodyComposition] carries the canonical [BodyCompositionSnapshot]
 *    (Round 2 Stage C consolidation — was `WeightSummary?` before). The
 *    hero composable builds a lb-shaped `WeightHeroDisplay` from the
 *    snapshot at render time. A snapshot with no weight reading still
 *    transitions to `Loaded`; the hero shows its empty state in that
 *    case (the "Connect Google Health" CTA lands in IMPL-AND-02).
 *  - [blood] is `Loaded(emptyList())` when no dashboard markers have
 *    readings. The panel hides itself.
 *  - [todaysDoses] is `Loaded(emptyList())` when nothing is scheduled
 *    today. The today card renders "No scheduled doses today".
 */
data class DashboardUiState(
    val bodyComposition: CardState<BodyCompositionSnapshot>,
    val blood: CardState<List<BloodMarkerSummary>>,
    val todaysDoses: CardState<List<TodaysDoseSummary>>,
) {
    companion object {
        val initial = DashboardUiState(
            bodyComposition = CardState.Loading,
            blood = CardState.Loading,
            todaysDoses = CardState.Loading,
        )
    }
}
