package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gte619n.healthfitness.feature.medical.add.AddMedicationScreen
import com.gte619n.healthfitness.feature.medical.detail.MedicationDetailScreen
import com.gte619n.healthfitness.feature.medical.list.MedicationsListScreen
import com.gte619n.healthfitness.feature.medical.nav.MedicationDetailRoute
import com.gte619n.healthfitness.feature.settings.SettingsScreen
import com.gte619n.healthfitness.feature.settings.profile.ProfileScreen
import com.gte619n.healthfitness.mobile.dashboard.PhoneTodayScreen

/**
 * The single `NavHost` for the phone app. Feature modules expose
 * `NavGraphBuilder` extensions that this aggregator calls; the rest are
 * `PlaceholderScreen` calls until the corresponding IMPL replaces them.
 *
 * `Route.Today` is the start destination — matches the existing behavior
 * where the dashboard is what the user sees the moment sign-in succeeds.
 *
 * `onSignedOut` is supplied by [SignedInScaffold] and bridges the
 * Settings sign-out action back to the app-root [AuthViewModel] so the
 * top-level `AppRoot` re-evaluates and switches to `SignInScreen`.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Route.Today,
        modifier = modifier,
    ) {
        composable<Route.Today> {
            PhoneTodayScreen(
                onSeeAllDoses = { navController.navigate(Route.Medications) },
            )
        }
        composable<Route.Body> { PlaceholderScreen("Body", nextImpl = "IMPL-AND-05") }
        composable<Route.Blood> { PlaceholderScreen("Blood", nextImpl = "IMPL-AND-04") }
        composable<Route.Workouts> { PlaceholderScreen("Workouts", nextImpl = "IMPL-AND-06") }

        // IMPL-AND-03: feature-medical replaces the placeholder.
        composable<Route.Medications> {
            MedicationsListScreen(
                onAdd = { navController.navigate(Route.AddMedication) },
                onMedicationClick = { id ->
                    navController.navigate(MedicationDetailRoute(id))
                },
            )
        }
        composable<Route.AddMedication> {
            AddMedicationScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<MedicationDetailRoute> {
            MedicationDetailScreen(onBack = { navController.popBackStack() })
        }

        composable<Route.Settings> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToProfile = { navController.navigate(Route.Profile) },
                onSignedOut = onSignedOut,
            )
        }
        composable<Route.Profile> {
            ProfileScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable<Route.DexaDetail> { PlaceholderScreen("DEXA detail", nextImpl = "IMPL-AND-05") }
        composable<Route.BloodReportDetail> { PlaceholderScreen("Blood report", nextImpl = "IMPL-AND-04") }
        composable<Route.GymDetail> { PlaceholderScreen("Gym", nextImpl = "IMPL-AND-06") }
    }
}
