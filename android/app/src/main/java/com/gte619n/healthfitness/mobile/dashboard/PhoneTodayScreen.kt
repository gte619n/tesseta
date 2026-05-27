package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.CardState
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.DashboardViewModel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun PhoneTodayScreen(
    onSeeAllDoses: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Resume-only refresh per spec — pull-to-refresh deferred.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 18.dp)
                .padding(top = 6.dp, bottom = 16.dp),
        ) {
            PhoneHeader()
            Spacer(Modifier.height(16.dp))
            PhoneVitalsGrid(weightCardState = ui.bodyComposition)
            Spacer(Modifier.height(11.dp))
            // TodayCard isn't wrapped in CardSwitch because the calories
            // / macros / workout sections render unconditionally from
            // fixtures; the doses preview is what becomes live data. An
            // error on the doses fetch surfaces inline as "no doses"
            // rather than swapping out the whole card body.
            TodayCard(
                modifier = Modifier.fillMaxWidth(),
                showHrInMeta = false,
                onSeeAllDoses = onSeeAllDoses,
            )
            Spacer(Modifier.height(11.dp))
            QuickLogTiles()
            if (DashboardFlags.showRecentFeedFixtures) {
                Spacer(Modifier.height(13.dp))
                RecentFeed(
                    entries = DashboardFallbacks.recentPhone,
                    showViewAll = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        BottomNav()
    }
}

@Composable
private fun PhoneHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = DashboardFallbacks.GREETING,
                style = Hf.type.headingLg.copy(fontSize = 18.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${DashboardFallbacks.DATE_WEEKDAY} · ${DashboardFallbacks.DATE_MONTH_DAY} · ${DashboardFallbacks.TIME}",
                style = Hf.type.monoSm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            IconButtonChip(
                icon = DashboardIcons.Bell,
                contentDescription = "Notifications",
                showDot = true,
            )
            AvatarSquare(initials = DashboardFallbacks.USER_INITIALS)
        }
    }
}

@Composable
private fun PhoneVitalsGrid(
    weightCardState: CardState<com.gte619n.healthfitness.domain.dashboard.WeightSummary?>,
) {
    // Weight card folds the body-composition card state into the existing
    // StatCard surface. Loading / Error render as the fallback Vital
    // shape so the 2×2 grid stays uniform; HRV / RHR / Readiness keep
    // their fixture cards while `DashboardFlags.showVitalsFixtures = true`.
    val weightVital = when (weightCardState) {
        is CardState.Loaded -> VitalFromWeight.weightVitalOrFallback(weightCardState.data)
        else -> DashboardFallbacks.vitals[0]
    }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = weightVital, modifier = Modifier.weight(1f))
            StatCard(stat = DashboardFallbacks.vitals[1], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = DashboardFallbacks.vitals[2], modifier = Modifier.weight(1f))
            StatCard(stat = DashboardFallbacks.vitals[3], modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickLogTiles() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        QuickTile(DashboardIcons.Barbell, "Workout", Modifier.weight(1f))
        QuickTile(DashboardIcons.Bowl, "Food", Modifier.weight(1f))
        QuickTile(DashboardIcons.Scale, "Weight", Modifier.weight(1f))
        QuickTile(DashboardIcons.Pill, "Med", Modifier.weight(1f))
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Hf.colors.accent,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm.copy(fontSize = 10.sp),
            color = Hf.colors.textPrimary,
        )
    }
}

@Composable
private fun BottomNav() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface),
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(Hf.colors.borderDefault),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                DashboardFallbacks.phoneBottomNav.forEach { dest ->
                    BottomNavItem(icon = dest.icon, label = dest.label, active = dest.active)
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(icon: ImageVector, label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (active) {
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(24.dp)
                    .background(Hf.colors.accent, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)),
            )
            Spacer(Modifier.height(10.dp))
        } else {
            Spacer(Modifier.height(12.dp))
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (active) Hf.colors.accent else Hf.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm.copy(fontSize = 10.sp),
            color = if (active) Hf.colors.accent else Hf.colors.textTertiary,
        )
    }
}
