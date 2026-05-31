package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.feature.medical.nav.MedicationRoutes
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.abs

@Composable
fun PhoneTodayScreen(
    onOpenGoals: () -> Unit = {},
    onNavigate: (route: String) -> Unit = {},
) {
    val vm: DashboardViewModel = hiltViewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
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
            PhoneVitalsGrid(ui = ui, weightUnit = weightUnit, onRetryWeight = vm::retryBodyComposition)
            Spacer(Modifier.height(11.dp))
            TodayCard(modifier = Modifier.fillMaxWidth(), showHrInMeta = false)
            Spacer(Modifier.height(11.dp))
            TodaysDosesCard(
                onSeeAll = { onNavigate(MedicationRoutes.LIST) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(11.dp))
            QuickLogTiles(onNavigate = onNavigate)
            Spacer(Modifier.height(13.dp))
            RecentFeed(entries = DashboardFallbacks.recentPhone, showViewAll = true, modifier = Modifier.fillMaxWidth())
        }
        BottomNav(onOpenGoals = onOpenGoals, onNavigate = onNavigate)
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
private fun PhoneVitalsGrid(ui: DashboardUiState, weightUnit: WeightUnit, onRetryWeight: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            // First card is the live Weight vital backed by body-composition.
            Box(modifier = Modifier.weight(1f)) {
                CardSwitch(
                    state = ui.bodyComposition,
                    placeholderHeightDp = 96,
                    onRetry = onRetryWeight,
                ) { summary ->
                    StatCard(stat = weightVital(summary, weightUnit), modifier = Modifier.fillMaxWidth())
                }
            }
            StatCard(stat = DashboardFallbacks.vitals[1], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = DashboardFallbacks.vitals[2], modifier = Modifier.weight(1f))
            StatCard(stat = DashboardFallbacks.vitals[3], modifier = Modifier.weight(1f))
        }
    }
}

/** Builds the Weight [Vital] card model from the live body-composition summary. */
private fun weightVital(s: WeightSummary?, weightUnit: WeightUnit): Vital =
    Vital(
        label = "Weight",
        icon = DashboardIcons.Scale,
        value = s?.let { UnitFormat.weightValueString(it.latestLb, weightUnit) } ?: "—",
        unit = UnitFormat.weightLabel(weightUnit),
        delta = s?.sevenDayDeltaLb?.let {
            VitalDelta(
                direction = if (it <= 0) ArrowDir.Down else ArrowDir.Up,
                value = UnitFormat.weightValueString(abs(it), weightUnit),
                window = "7d",
                tone = if (it <= 0) Tone.Good else Tone.Warn,
            )
        },
        sparkline = normalizedSparkline(s?.series),
    )

/**
 * Maps a weight series into ~9 points in the 0..20 sparkline space (min → 2,
 * max → 18). Falls back to the fixture-style flat list when no data is present.
 */
private fun normalizedSparkline(series: List<Double>?): List<Float> {
    if (series.isNullOrEmpty()) return DashboardFallbacks.vitals[0].sparkline
    val sampled = sampleTo(series, 9)
    val min = sampled.min()
    val max = sampled.max()
    val span = (max - min).takeIf { it != 0.0 } ?: 1.0
    return sampled.map { v -> (2.0 + ((v - min) / span) * 16.0).toFloat() }
}

private fun sampleTo(series: List<Double>, count: Int): List<Double> {
    if (series.size <= count) return series
    return (0 until count).map { i ->
        val idx = (i.toFloat() / (count - 1) * (series.size - 1)).toInt()
        series[idx]
    }
}

@Composable
private fun QuickLogTiles(onNavigate: (route: String) -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        QuickTile(
            DashboardIcons.Barbell,
            "Workout",
            Modifier.weight(1f),
            onClick = { onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.WORKOUTS) },
        )
        // IMPL-13: the "Food" tile opens the nutrition Today screen.
        QuickTile(
            DashboardIcons.Bowl,
            "Food",
            Modifier.weight(1f),
            onClick = { onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.NUTRITION) },
        )
        QuickTile(
            DashboardIcons.Scale,
            "Weight",
            Modifier.weight(1f),
            onClick = { onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.BODY) },
        )
        QuickTile(
            DashboardIcons.Pill,
            "Med",
            Modifier.weight(1f),
            onClick = { onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.MEDICATIONS) },
        )
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit = {}) {
    Column(
        modifier = modifier
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .clickable { onClick() }
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
private fun BottomNav(onOpenGoals: () -> Unit, onNavigate: (route: String) -> Unit = {}) {
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
                    // "More" → the feature hub; "Log" → Goals so the phone
                    // keeps a path to the goals surface (IMPL-12).
                    val onClick: () -> Unit = when (dest.label) {
                        "More" -> ({ onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.MORE) })
                        "Log" -> onOpenGoals
                        else -> ({})
                    }
                    BottomNavItem(
                        icon = dest.icon,
                        label = dest.label,
                        active = dest.active,
                        onClick = onClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomNavItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() },
    ) {
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
