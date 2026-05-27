package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.gte619n.healthfitness.feature.settings.more.MoreRoute

/**
 * Single declaration of the top-level destinations shown in the bottom
 * nav (compact) and the foldable sidebar (medium/expanded). The two
 * surfaces diverge intentionally — the bottom nav can only fit a
 * handful of icons before legibility suffers, so it collapses the
 * tail destinations into a "More" overflow that opens the
 * [MoreRoute] screen. The foldable sidebar has the vertical room to
 * expose every primary destination at once and so iterates
 * [PrimaryDestinations] directly without the overflow indirection.
 */
data class TopLevelDestination(
    val route: Any,
    val label: String,
    val icon: ImageVector,
)

val PrimaryDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(Route.Today, "Today", Icons.Outlined.Dashboard),
    TopLevelDestination(Route.Body, "Body", Icons.Outlined.AccountTree),
    TopLevelDestination(Route.Blood, "Blood", Icons.Outlined.WaterDrop),
    TopLevelDestination(Route.Workouts, "Workouts", Icons.Outlined.FitnessCenter),
    TopLevelDestination(Route.Medications, "Meds", Icons.Outlined.Medication),
    TopLevelDestination(Route.Settings, "Settings", Icons.Outlined.Settings),
)

/**
 * Round 2 Stage B reshape — the phone bottom bar now exposes four
 * primary destinations (Today / Body / Meds) and a single "More"
 * overflow tab that opens the in-app menu listing the remaining
 * destinations (Blood, Workouts, Settings, Sign out). Keeping the
 * bar at four labels gives every tap target a wider hit area and
 * leaves headroom for a future fifth-tab promotion without
 * re-shuffling the layout.
 */
val BottomNavDestinations: List<TopLevelDestination> = listOf(
    TopLevelDestination(Route.Today, "Today", Icons.Outlined.Home),
    PrimaryDestinations[1], // Body
    PrimaryDestinations[4], // Meds (IMPL-AND-03)
    TopLevelDestination(MoreRoute, "More", Icons.Outlined.MoreHoriz),
)
