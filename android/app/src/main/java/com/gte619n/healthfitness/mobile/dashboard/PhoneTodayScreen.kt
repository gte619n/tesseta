package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onNavigate: (route: String) -> Unit = {},
) {
    val vm: DashboardViewModel = hiltViewModel()
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    // Long-pressing the bottom-nav "Log" button opens this quick-log menu.
    var showLogMenu by remember { mutableStateOf(false) }
    val openFoodCapture = { onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.NUTRITION_CAPTURE) }
    Box(modifier = Modifier.fillMaxSize()) {
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
                PhoneHeader(user = ui.user, lastUpdated = ui.lastUpdated)
                Spacer(Modifier.height(16.dp))
                PhoneVitalsGrid(ui = ui, weightUnit = weightUnit, onRetryWeight = vm::retryBodyComposition)
                // Coaching is the primary surface: one tap to start/resume today's
                // workout (renders nothing on a rest day).
                TodayWorkoutCard(onNavigate = onNavigate, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(11.dp))
                TodayCard(
                    modifier = Modifier.fillMaxWidth(),
                    nutrition = (ui.nutrition as? CardState.Loaded)?.data,
                )
                Spacer(Modifier.height(11.dp))
                TodaysDosesCard(
                    onSeeAll = { onNavigate(MedicationRoutes.LIST) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(11.dp))
                QuickLogTiles(onNavigate = onNavigate)
                Spacer(Modifier.height(13.dp))
                val recentEntries = if (DashboardFlags.showRecentFeedFixtures) {
                    DashboardFallbacks.recentPhone
                } else {
                    (ui.recentActivity as? CardState.Loaded)?.data.orEmpty().toLogEntries(foldable = false)
                }
                RecentFeed(entries = recentEntries, showViewAll = true, modifier = Modifier.fillMaxWidth())
            }
            BottomNav(
                onLogTap = openFoodCapture,
                onLogLongPress = { showLogMenu = true },
                onNavigate = onNavigate,
            )
        }
        if (showLogMenu) {
            LogMenuOverlay(
                onDismiss = { showLogMenu = false },
                onFood = {
                    showLogMenu = false
                    openFoodCapture()
                },
            )
        }
    }
}

@Composable
private fun PhoneHeader(user: DashboardUser?, lastUpdated: java.time.Instant?) {
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
                text = lastUpdatedLabel(lastUpdated),
                style = Hf.type.monoSm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            IconButtonChip(
                icon = DashboardIcons.Bell,
                contentDescription = "Notifications",
                enabled = false,
            )
            AvatarSquare(
                initials = user?.initials ?: DashboardFallbacks.USER_INITIALS,
                photoUrl = user?.photoUrl,
            )
        }
    }
}

@Composable
private fun PhoneVitalsGrid(ui: DashboardUiState, weightUnit: WeightUnit, onRetryWeight: () -> Unit) {
    // Tile order: Weight (live), Resting HR, HRV, Sleep, Steps.
    val metrics = (ui.dailyMetrics as? CardState.Loaded)?.data.orEmpty()
    // Each vital does a sort + mapNotNull + sparkline pass; memoise on `metrics`
    // so they only recompute when the underlying series actually changes.
    val rhr = remember(metrics) { restingHrVital(metrics) }
    val hrv = remember(metrics) { hrvVital(metrics) }
    val sleep = remember(metrics) { sleepVital(metrics) }
    val steps = remember(metrics) { stepsVital(metrics) }
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
            StatCard(stat = rhr, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = hrv, modifier = Modifier.weight(1f))
            StatCard(stat = sleep, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = steps, modifier = Modifier.weight(1f))
            // Spacer keeps the last row's tile width consistent with the grid.
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/** Builds the Body [Vital] card model from the live body-composition summary. */
fun weightVital(s: WeightSummary?, weightUnit: WeightUnit): Vital =
    Vital(
        label = "Body",
        icon = DashboardIcons.Body,
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
private fun BottomNav(
    onLogTap: () -> Unit,
    onLogLongPress: () -> Unit,
    onNavigate: (route: String) -> Unit = {},
) {
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
                    // "More" → the feature hub. "Log" → tap jumps straight to
                    // the food camera capture; long-press opens the quick-log
                    // menu (Workout/Weight/Food).
                    val onClick: () -> Unit = when (dest.label) {
                        "More" -> ({ onNavigate(com.gte619n.healthfitness.mobile.nav.Routes.MORE) })
                        "Log" -> onLogTap
                        else -> ({})
                    }
                    BottomNavItem(
                        icon = dest.icon,
                        label = dest.label,
                        active = dest.active,
                        onClick = onClick,
                        onLongClick = if (dest.label == "Log") onLogLongPress else null,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable { onClick() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = clickModifier,
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

/**
 * Quick-log menu shown when the bottom-nav "Log" button is long-pressed. A
 * full-screen scrim dims the dashboard; a vertical stack of options sits above
 * the Log button (Workout, Weight, Food, top-to-bottom). Workout and Weight are
 * dimmed/disabled for now; Food jumps to the nutrition camera capture.
 */
@Composable
private fun LogMenuOverlay(onDismiss: () -> Unit, onFood: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // A single popup menu card, anchored above the "Log" nav item (index 1
        // of Today/Log/Trends/More). It sizes to its widest row
        // (IntrinsicSize.Max) so labels never wrap, with a comfortable minimum.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 16.dp, bottom = 80.dp)
                .widthIn(min = 200.dp)
                .width(IntrinsicSize.Max)
                .background(Hf.colors.surface, RoundedCornerShape(14.dp))
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(14.dp)),
        ) {
            LogMenuItem(DashboardIcons.Barbell, "Workout", enabled = false)
            LogMenuDivider()
            LogMenuItem(DashboardIcons.Scale, "Weight", enabled = false)
            LogMenuDivider()
            LogMenuItem(DashboardIcons.Bowl, "Food", enabled = true, onClick = onFood)
        }
    }
}

@Composable
private fun LogMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(Hf.colors.borderDefault),
    )
}

@Composable
private fun LogMenuItem(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    val tint = if (enabled) Hf.colors.accent else Hf.colors.textTertiary.copy(alpha = 0.4f)
    val textColor = if (enabled) Hf.colors.textPrimary else Hf.colors.textTertiary.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm.copy(fontSize = 11.sp),
            color = textColor,
            maxLines = 1,
            softWrap = false,
        )
    }
}
