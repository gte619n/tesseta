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
 * The phone bottom nav is space-constrained — show five primary
 * destinations. IMPL-AND-03 promotes Meds out of the More menu since
 * the medications screen now ships with full CRUD + the dashboard
 * Today's Doses card deep-links into it. Settings remains under a
 * "More" entry that opens the Settings screen directly; the spec
 * leaves room for a future "More" sheet when there are more secondary
 * surfaces.
 */
val BottomNavDestinations: List<TopLevelDestination> = listOf(
    PrimaryDestinations[0], // Today
    PrimaryDestinations[1], // Body
    PrimaryDestinations[2], // Blood
    PrimaryDestinations[4], // Meds (IMPL-AND-03)
    TopLevelDestination(Route.Settings, "More", Icons.Outlined.Settings),
)
