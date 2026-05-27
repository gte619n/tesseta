package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Single declaration of the top-level destinations shown in the bottom
 * nav (compact) and the foldable sidebar (medium/expanded). The same list
 * drives both — width class is a layout concern, not a routing concern.
 */
data class TopLevelDestination(
    val route: Route,
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
 * The phone bottom nav is space-constrained — four primary
 * destinations + a "More" tail. IMPL-AND-06 promotes Workouts into
 * the bottom nav now that the gym/equipment surface is live. Blood
 * drops out of the bottom nav (still in the foldable sidebar and
 * accessible from the dashboard panel) so the bar stays at five
 * tappable slots.
 */
val BottomNavDestinations: List<TopLevelDestination> = listOf(
    PrimaryDestinations[0], // Today
    PrimaryDestinations[1], // Body
    PrimaryDestinations[3], // Workouts (IMPL-AND-06)
    PrimaryDestinations[4], // Meds (IMPL-AND-03)
    TopLevelDestination(Route.Settings, "More", Icons.Outlined.Settings),
)
