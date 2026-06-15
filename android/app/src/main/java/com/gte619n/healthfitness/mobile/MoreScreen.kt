package com.gte619n.healthfitness.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.mobile.BuildConfig
import com.gte619n.healthfitness.mobile.dashboard.DashboardIcons
import com.gte619n.healthfitness.mobile.nav.Routes
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Phone-only "More" hub. Surfaces the parity features that don't have a
 * dedicated bottom-nav tab (workouts, blood, body, nutrition, medications,
 * goals, settings) as a simple list of navigable rows. Sits directly on the
 * canvas — no white form fill — per the android UI conventions.
 */
@Composable
fun MoreScreen(
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Hf.colors.textPrimary,
                )
            }
            SectionTitle(text = "More")
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        ) {
            // The debug Sync log row is dev-only (Workstream B) — keep it out of
            // release builds so it doesn't read as a user feature.
            val rows = if (BuildConfig.DEBUG) {
                MoreRows + MoreItem("Sync log", DashboardIcons.Settings, Routes.SYNC_LOG)
            } else {
                MoreRows
            }
            HfCard(transparent = true, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    rows.forEachIndexed { index, item ->
                        if (index > 0) {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(0.5.dp)
                                    .background(Hf.colors.borderDefault),
                            )
                        }
                        MoreRow(
                            icon = item.icon,
                            label = item.label,
                            onClick = { onNavigate(item.route) },
                        )
                    }
                }
            }
        }
    }
}

private data class MoreItem(val label: String, val icon: ImageVector, val route: String)

private val MoreRows = listOf(
    MoreItem("Workouts", DashboardIcons.Barbell, Routes.WORKOUTS),
    MoreItem("Blood", DashboardIcons.Droplet, Routes.BLOOD),
    MoreItem("Body", DashboardIcons.BodyScan, Routes.BODY),
    MoreItem("Nutrition", DashboardIcons.Bowl, Routes.NUTRITION),
    MoreItem("Medications", DashboardIcons.Pill, Routes.MEDICATIONS),
    MoreItem("Goals", DashboardIcons.Route, Routes.GOALS_LIST),
    MoreItem("Settings", DashboardIcons.Settings, Routes.SETTINGS),
)

@Composable
private fun MoreRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Hf.colors.accent,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = Hf.type.bodyMd,
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "›",
            style = Hf.type.bodyMd,
            color = Hf.colors.textTertiary,
        )
    }
}
