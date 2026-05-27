package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gte619n.healthfitness.mobile.dashboard.PhoneTodayScreen

/**
 * The single `NavHost` for the phone app. Feature modules expose
 * `NavGraphBuilder` extensions that this aggregator calls; the rest are
 * `PlaceholderScreen` calls until the corresponding IMPL replaces them.
 *
 * `Route.Today` is the start destination — matches the existing behavior
 * where the dashboard is what the user sees the moment sign-in succeeds.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Today,
        modifier = modifier,
    ) {
        composable<Route.Today> {
            // Existing dashboard, untouched in this IMPL. AND-01 will swap
            // its fixtures-backed body for live data + a HiltViewModel.
            PhoneTodayScreen()
        }
        composable<Route.Body> { PlaceholderScreen("Body", nextImpl = "IMPL-AND-05") }
        composable<Route.Blood> { PlaceholderScreen("Blood", nextImpl = "IMPL-AND-04") }
        composable<Route.Workouts> { PlaceholderScreen("Workouts", nextImpl = "IMPL-AND-06") }
        composable<Route.Medications> { PlaceholderScreen("Medications", nextImpl = "IMPL-AND-03") }
        composable<Route.Settings> { PlaceholderScreen("Settings", nextImpl = "IMPL-AND-02") }

        composable<Route.DexaDetail> { PlaceholderScreen("DEXA detail", nextImpl = "IMPL-AND-05") }
        composable<Route.BloodReportDetail> { PlaceholderScreen("Blood report", nextImpl = "IMPL-AND-04") }
        composable<Route.MedicationDetail> { PlaceholderScreen("Medication", nextImpl = "IMPL-AND-03") }
        composable<Route.GymDetail> { PlaceholderScreen("Gym", nextImpl = "IMPL-AND-06") }
    }
}
