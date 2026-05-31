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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.TessetaMark
import com.gte619n.healthfitness.ui.TessetaMarkVariant
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun FoldableDashboardScreen(onOpenGoals: () -> Unit = {}, onOpenNutrition: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            FoldableSidebar(onOpenGoals = onOpenGoals, onOpenNutrition = onOpenNutrition)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 22.dp)
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                FoldableTopBar()
                Spacer(Modifier.height(18.dp))
                FoldableVitalsRow()
                Spacer(Modifier.height(11.dp))
                BodyCompositionHero()
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BloodPanel(showRangeLabels = false, modifier = Modifier.weight(1f))
                    TodayCard(modifier = Modifier.weight(1f), showHrInMeta = false)
                }
                Spacer(Modifier.height(10.dp))
                RecentFeed(entries = DashboardFixtures.recentFoldable, showViewAll = false, modifier = Modifier.fillMaxWidth())
            }
        }
        // Decorative hinge crease running down the middle.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(Hf.colors.textPrimary.copy(alpha = 0.07f))
                .align(Alignment.Center),
        )
    }
}

@Composable
private fun FoldableSidebar(onOpenGoals: () -> Unit = {}, onOpenNutrition: () -> Unit = {}) {
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
        DashboardFixtures.foldableNav.forEach { dest ->
            FoldableNavIcon(
                icon = dest.icon,
                active = dest.active,
                alert = dest.alert,
                contentDescription = dest.label,
                onClick = when (dest.label) {
                    "Goals" -> onOpenGoals
                    "Nutrition" -> onOpenNutrition
                    else -> ({})
                },
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
        )
        AvatarSquare(initials = DashboardFixtures.USER_INITIALS, size = 38)
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
private fun FoldableTopBar() {
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
                text = "${DashboardFixtures.DATE_WEEKDAY} · ${DashboardFixtures.DATE_MONTH_DAY} · ${DashboardFixtures.TIME} · ${DashboardFixtures.TZ}",
                style = Hf.type.monoSm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier
                    .background(Hf.colors.surface, RoundedCornerShape(7.dp))
                    .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = DashboardIcons.Calendar,
                    contentDescription = null,
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "90D",
                    style = Hf.type.monoSm.copy(fontSize = 11.sp),
                    color = Hf.colors.textSecondary,
                )
                Icon(
                    imageVector = DashboardIcons.ChevronDown,
                    contentDescription = null,
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(11.dp),
                )
            }
            IconButtonChip(
                icon = DashboardIcons.Bell,
                contentDescription = "Notifications",
                showDot = true,
                size = 32,
            )
        }
    }
}

@Composable
private fun FoldableVitalsRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashboardFixtures.vitals.forEachIndexed { i, stat ->
            StatCard(
                stat = stat,
                overrideLabel = DashboardFixtures.vitalsShortLabels[i],
                valueSizeSp = 19,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BodyCompositionHero() {
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
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroNumeric(primary = "189.2", unit = "lb", delta = "↓ 3.6 90d", primarySizeSp = 30, unitSizeSp = 12)
                        Box(modifier = Modifier.width(0.5.dp).height(34.dp).background(Hf.colors.borderDefault))
                        HeroNumeric(primary = "17.4", unit = "% fat", delta = "↓ 0.8 pts", primarySizeSp = 15, unitSizeSp = 10)
                        HeroNumeric(primary = "156.3", unit = "lean", delta = "↑ 1.2", primarySizeSp = 15, unitSizeSp = 10)
                    }
                }
                Segment(active = "90d")
            }
            Spacer(Modifier.height(11.dp))
            WeightChart(modifier = Modifier.fillMaxWidth())
        }
    }
}

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
        Spacer(Modifier.height(4.dp))
        Text(
            text = delta,
            style = Hf.type.monoSm.copy(fontSize = 10.sp),
            color = Hf.colors.good,
        )
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
