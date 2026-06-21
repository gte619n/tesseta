package com.gte619n.healthfitness.feature.medical.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Full-screen "Today's doses" checklist — the destination a medication-reminder
 * notification deep-links into (IMPL-16: per-med checkboxes instead of the
 * notification's "Take all" lump action). Reuses [TodaysDosesCard], the same
 * checkbox list embedded on the dashboard, so checking a dose here logs
 * adherence through the shared [TodaysDosesViewModel] (optimistic, offline-safe).
 */
@Composable
fun TodaysDosesScreen(
    onBack: () -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Today's doses",
            subtitle = "Check off what you've taken",
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TodaysDosesCard(onSeeAll = onSeeAll, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}
