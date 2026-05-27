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
 * The phone bottom nav is space-constrained — show four primary
 * destinations plus a "More" entry that opens the Settings screen.
 * IMPL-AND-02 wires the "More" tab to `Route.Settings` directly; future
 * IMPLs may replace it with a sheet that lists Meds / Settings / Help /
 * etc. once there are more secondary surfaces.
 */
val BottomNavDestinations: List<TopLevelDestination> = listOf(
    PrimaryDestinations[0], // Today
    PrimaryDestinations[1], // Body
    PrimaryDestinations[2], // Blood
    PrimaryDestinations[3], // Workouts
    TopLevelDestination(Route.Settings, "More", Icons.Outlined.Settings),
)
