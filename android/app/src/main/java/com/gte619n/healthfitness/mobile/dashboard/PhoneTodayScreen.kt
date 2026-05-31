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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun PhoneTodayScreen(onOpenGoals: () -> Unit = {}, onOpenNutrition: () -> Unit = {}) {
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
            PhoneVitalsGrid()
            Spacer(Modifier.height(11.dp))
            TodayCard(modifier = Modifier.fillMaxWidth(), showHrInMeta = false)
            Spacer(Modifier.height(11.dp))
            QuickLogTiles(onOpenNutrition = onOpenNutrition)
            Spacer(Modifier.height(13.dp))
            RecentFeed(entries = DashboardFixtures.recentPhone, showViewAll = true, modifier = Modifier.fillMaxWidth())
        }
        BottomNav(onOpenGoals = onOpenGoals)
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
                text = DashboardFixtures.GREETING,
                style = Hf.type.headingLg.copy(fontSize = 18.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${DashboardFixtures.DATE_WEEKDAY} · ${DashboardFixtures.DATE_MONTH_DAY} · ${DashboardFixtures.TIME}",
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
            AvatarSquare(initials = DashboardFixtures.USER_INITIALS)
        }
    }
}

@Composable
private fun PhoneVitalsGrid() {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = DashboardFixtures.vitals[0], modifier = Modifier.weight(1f))
            StatCard(stat = DashboardFixtures.vitals[1], modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            StatCard(stat = DashboardFixtures.vitals[2], modifier = Modifier.weight(1f))
            StatCard(stat = DashboardFixtures.vitals[3], modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickLogTiles(onOpenNutrition: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        QuickTile(DashboardIcons.Barbell, "Workout", Modifier.weight(1f))
        // IMPL-13: the "Food" tile opens the nutrition Today screen.
        QuickTile(DashboardIcons.Bowl, "Food", Modifier.weight(1f), onClick = onOpenNutrition)
        QuickTile(DashboardIcons.Scale, "Weight", Modifier.weight(1f))
        QuickTile(DashboardIcons.Pill, "Med", Modifier.weight(1f))
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
private fun BottomNav(onOpenGoals: () -> Unit) {
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
                DashboardFixtures.phoneBottomNav.forEach { dest ->
                    // IMPL-12: phone reaches Goals via the "More" tab (Goals
                    // lives under More per the spec's phone nav guidance).
                    val onClick = if (dest.label == "More") onOpenGoals else ({})
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
