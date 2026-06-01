package com.gte619n.healthfitness.feature.workouts.program

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Workouts hub (IMPL-AND-15). The Workouts destination is a hub with two cards
 * — Gyms (IMPL-AND-06) and Programs (read-only). Pure navigation, no ViewModel.
 */
@Composable
fun WorkoutsHubScreen(
    onBack: () -> Unit,
    onOpenGyms: () -> Unit,
    onOpenPrograms: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Workouts",
            subtitle = "Your gyms and training programs",
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HubCard(
                icon = Icons.Outlined.FitnessCenter,
                title = "Gyms",
                description = "Your gyms, equipment, and hours.",
                onClick = onOpenGyms,
            )
            HubCard(
                icon = Icons.Outlined.ListAlt,
                title = "Programs",
                description = "Your periodized training programs.",
                onClick = onOpenPrograms,
            )
        }
    }
}

@Composable
private fun HubCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Hf.colors.accent,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = Hf.type.headingMd.copy(fontSize = 15.sp),
                    color = Hf.colors.textPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(description, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            }
            Text("›", style = Hf.type.headingLg, color = Hf.colors.textTertiary)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun WorkoutsHubPreview() {
    HealthFitnessTheme {
        WorkoutsHubScreen(onBack = {}, onOpenGyms = {}, onOpenPrograms = {})
    }
}
