package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.prefs.UnitFormat
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.feature.blood.dashboard.DashboardBloodViewModel
import com.gte619n.healthfitness.feature.medical.nav.MedicationRoutes
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesCard
import com.gte619n.healthfitness.ui.TessetaMark
import com.gte619n.healthfitness.ui.TessetaMarkVariant
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.Instant

@Composable
fun FoldableDashboardScreen(
    onOpenGoals: () -> Unit = {},
    onNavigate: (route: String) -> Unit = {},
) {
    val vm: DashboardViewModel = hiltViewModel()
    val bloodVm: DashboardBloodViewModel = hiltViewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val bloodMarkers by bloodVm.markers.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refresh()
        bloodVm.refresh()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            FoldableSidebar(user = ui.user, onOpenGoals = onOpenGoals, onNavigate = onNavigate)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                FoldableTopBar(lastUpdated = ui.lastUpdated)
                Spacer(Modifier.height(18.dp))
                FoldableVitalsRow(ui = ui, weightUnit = weightUnit, onRetryWeight = vm::retryBodyComposition)
                Spacer(Modifier.height(11.dp))
                CardSwitch(
                    state = ui.bodyComposition,
                    placeholderHeightDp = 200,
                    onRetry = vm::retryBodyComposition,
                ) { summary ->
                    BodyCompositionHero(summary = summary, weightUnit = weightUnit)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        BloodPanel(
                            markers = bloodMarkers,
                            showRangeLabels = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TodayCard(
                        modifier = Modifier.weight(1f),
                        nutrition = (ui.nutrition as? CardState.Loaded)?.data,
                    )
                }
                Spacer(Modifier.height(10.dp))
                TodaysDosesCard(
                    onSeeAll = { onNavigate(MedicationRoutes.LIST) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                val recentEntries = if (DashboardFlags.showRecentFeedFixtures) {
                    DashboardFallbacks.recentFoldable
                } else {
                    (ui.recentActivity as? CardState.Loaded)?.data.orEmpty().toLogEntries(foldable = true)
                }
                RecentFeed(entries = recentEntries, showViewAll = false, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FoldableSidebar(
    user: DashboardUser? = null,
    onOpenGoals: () -> Unit = {},
    onNavigate: (route: String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(64.dp)
            .background(Hf.colors.canvasMuted)
            .border(0.5.dp, Hf.colors.borderStrong, shape = RoundedCornerShape(0.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // Logo — Tesseta mark, ink squircle per LOGO-SPEC.md (foldable
        // section). 38 dp matches the spec's foldable rail size.
        TessetaMark(variant = TessetaMarkVariant.DARK, size = 38.dp)
        Spacer(Modifier.height(9.dp))
        DashboardFallbacks.foldableNav.forEach { dest ->
            val onClick: () -> Unit = when (dest.label) {
                "Goals" -> onOpenGoals
                "Body" -> ({ onNavigate(com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes.BODY) })
                "Blood" -> ({ onNavigate(com.gte619n.healthfitness.feature.blood.nav.BloodRoutes.OVERVIEW) })
                "Workouts" -> ({ onNavigate(com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes.HUB) })
                "Meds" -> ({ onNavigate(com.gte619n.healthfitness.feature.medical.nav.MedicationRoutes.LIST) })
                "Nutrition" -> ({ onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.NUTRITION) })
                else -> ({})
            }
            FoldableNavIcon(
                icon = dest.icon,
                active = dest.active,
                alert = dest.alert,
                contentDescription = dest.label,
                onClick = onClick,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderStrong),
        )
        Spacer(Modifier.height(12.dp))
        FoldableNavIcon(
            icon = DashboardIcons.Settings,
            active = false,
            alert = false,
            contentDescription = "Settings",
            onClick = { onNavigate(com.gte619n.healthfitness.feature.settings.nav.SettingsRoutes.SETTINGS) },
        )
        AvatarSquare(
            initials = user?.initials ?: DashboardFallbacks.USER_INITIALS,
            photoUrl = user?.photoUrl,
            size = 38,
        )
    }
}

@Composable
private fun FoldableNavIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    alert: Boolean,
    contentDescription: String,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(38.dp)
            .clickable { onClick() }
            .background(
                if (active) Hf.colors.accentBg else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(vertical = 9.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Hf.colors.accent, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) Hf.colors.accentDim else Hf.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        if (alert) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(6.dp)
                    .background(Hf.colors.warn, RoundedCornerShape(50))
                    .border(1.dp, Hf.colors.canvasMuted, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun FoldableTopBar(lastUpdated: Instant?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Dashboard",
                style = Hf.type.headingLg.copy(fontSize = 20.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = lastUpdatedLabel(lastUpdated),
                style = Hf.type.monoSm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        IconButtonChip(
            icon = DashboardIcons.Bell,
            contentDescription = "Notifications",
            enabled = false,
            size = 32,
        )
    }
}

@Composable
private fun FoldableVitalsRow(
    ui: DashboardUiState,
    weightUnit: WeightUnit,
    onRetryWeight: () -> Unit,
) {
    // Tile order: Weight (live), Resting HR, HRV, Sleep, Steps.
    val metrics = (ui.dailyMetrics as? CardState.Loaded)?.data.orEmpty()
    // Each vital does a sort + mapNotNull + sparkline pass; memoise on `metrics`
    // so they only recompute when the underlying series actually changes.
    val tiles = remember(metrics) {
        listOf(
            restingHrVital(metrics) to "RHR",
            hrvVital(metrics) to "HRV",
            sleepVital(metrics) to "Sleep",
            stepsVital(metrics) to "Steps",
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // First tile is the live Weight vital backed by body-composition.
        Box(modifier = Modifier.weight(1f)) {
            CardSwitch(
                state = ui.bodyComposition,
                placeholderHeightDp = 96,
                onRetry = onRetryWeight,
            ) { summary ->
                StatCard(
                    stat = weightVital(summary, weightUnit),
                    overrideLabel = "Weight",
                    valueSizeSp = 19,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        tiles.forEach { (stat, shortLabel) ->
            StatCard(
                stat = stat,
                overrideLabel = shortLabel,
                valueSizeSp = 19,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BodyCompositionHero(summary: WeightSummary?, weightUnit: WeightUnit) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    SectionTitle("Body composition")
                    Spacer(Modifier.height(10.dp))
                    if (summary == null) {
                        Text(
                            text = "No body-composition data yet",
                            style = Hf.type.bodySm.copy(fontSize = 11.sp),
                            color = Hf.colors.textTertiary,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            HeroNumeric(
                                primary = UnitFormat.weightValueString(summary.latestLb, weightUnit),
                                unit = UnitFormat.weightLabel(weightUnit),
                                delta = deltaLabel(summary.ninetyDayDeltaLb?.let { UnitFormat.weightValue(it, weightUnit) }, "90d"),
                                primarySizeSp = 30,
                                unitSizeSp = 12,
                            )
                            Box(modifier = Modifier.width(0.5.dp).height(34.dp).background(Hf.colors.borderDefault))
                            HeroNumeric(
                                primary = summary.latestBodyFatPct?.let { "%.1f".format(it) } ?: "—",
                                unit = "% fat",
                                delta = "",
                                primarySizeSp = 15,
                                unitSizeSp = 10,
                            )
                            HeroNumeric(
                                primary = summary.latestLeanMassLb?.let { UnitFormat.weightValueString(it, weightUnit) } ?: "—",
                                unit = "lean",
                                delta = "",
                                primarySizeSp = 15,
                                unitSizeSp = 10,
                            )
                        }
                    }
                }
                Segment(active = "90d")
            }
            Spacer(Modifier.height(11.dp))
            if (summary != null) {
                WeightChart(
                    series = summary.series.map { it.toFloat() },
                    yMin = summary.yMin.toFloat(),
                    yMax = summary.yMax.toFloat(),
                    xLabels = summary.xLabels,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun deltaLabel(delta: Double?, window: String): String =
    delta?.let {
        val arrow = if (it <= 0) "↓" else "↑"
        "$arrow %.1f %s".format(kotlin.math.abs(it), window)
    } ?: ""

@Composable
private fun HeroNumeric(
    primary: String,
    unit: String,
    delta: String,
    primarySizeSp: Int,
    unitSizeSp: Int,
) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = primary,
                style = Hf.type.displayLg.copy(fontSize = primarySizeSp.sp, lineHeight = primarySizeSp.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = unit,
                style = Hf.type.bodySm.copy(fontSize = unitSizeSp.sp),
                color = Hf.colors.textTertiary,
            )
        }
        if (delta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = delta,
                style = Hf.type.monoSm.copy(fontSize = 10.sp),
                color = Hf.colors.good,
            )
        }
    }
}

@Composable
private fun Segment(active: String) {
    val options = listOf("30d", "90d", "1y", "All")
    Row(
        modifier = Modifier
            .background(Hf.colors.canvas, RoundedCornerShape(6.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        options.forEach { o ->
            val isActive = o == active
            Box(
                modifier = Modifier
                    .background(
                        if (isActive) Hf.colors.surface else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(4.dp),
                    )
                    .let {
                        if (isActive) it.border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(4.dp)) else it
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = o,
                    style = Hf.type.bodyMd.copy(fontSize = 10.sp),
                    color = if (isActive) Hf.colors.textPrimary else Hf.colors.textTertiary,
                )
            }
        }
    }
}
