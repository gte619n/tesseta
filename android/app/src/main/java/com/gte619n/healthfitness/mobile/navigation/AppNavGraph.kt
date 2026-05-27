package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.gte619n.healthfitness.feature.blood.add.AddReadingScreen
import com.gte619n.healthfitness.feature.blood.detail.MarkerDetailScreen
import com.gte619n.healthfitness.feature.blood.nav.AddReadingRoute
import com.gte619n.healthfitness.feature.blood.nav.MarkerDetailRoute
import com.gte619n.healthfitness.feature.blood.nav.ReportDetailRoute
import com.gte619n.healthfitness.feature.blood.nav.UploadReportRoute
import com.gte619n.healthfitness.feature.blood.overview.BloodOverviewScreen
import com.gte619n.healthfitness.feature.blood.report.ReportDetailScreen
import com.gte619n.healthfitness.feature.blood.upload.UploadLabReportScreen
import com.gte619n.healthfitness.feature.bodycomposition.detail.DexaScanDetailScreen
import com.gte619n.healthfitness.feature.bodycomposition.nav.DexaScanDetailRoute
import com.gte619n.healthfitness.feature.bodycomposition.nav.UploadDexaRoute
import com.gte619n.healthfitness.feature.bodycomposition.overview.BodyCompositionScreen
import com.gte619n.healthfitness.feature.bodycomposition.upload.UploadDexaScreen
import com.gte619n.healthfitness.feature.medical.add.AddMedicationScreen
import com.gte619n.healthfitness.feature.medical.detail.MedicationDetailScreen
import com.gte619n.healthfitness.feature.medical.list.MedicationsListScreen
import com.gte619n.healthfitness.feature.medical.nav.MedicationDetailRoute
import com.gte619n.healthfitness.feature.settings.SettingsScreen
import com.gte619n.healthfitness.feature.settings.profile.ProfileScreen
import com.gte619n.healthfitness.feature.workouts.addequipment.AddEquipmentSheet
import com.gte619n.healthfitness.feature.workouts.create.NewGymScreen
import com.gte619n.healthfitness.feature.workouts.detail.GymDetailScreen
import com.gte619n.healthfitness.feature.workouts.edit.EditGymScreen
import com.gte619n.healthfitness.feature.workouts.list.GymsListScreen
import com.gte619n.healthfitness.feature.workouts.nav.EditGymRoute
import com.gte619n.healthfitness.feature.workouts.nav.GymDetailRoute
import com.gte619n.healthfitness.feature.workouts.nav.GymsListRoute
import com.gte619n.healthfitness.feature.workouts.nav.NewGymRoute
import com.gte619n.healthfitness.feature.workouts.override.EquipmentOverrideSheet
import com.gte619n.healthfitness.mobile.dashboard.PhoneTodayScreen
import androidx.navigation.compose.dialog
import androidx.navigation.toRoute
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable

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
        // IMPL-AND-05: feature-body-composition replaces the placeholder.
        // Overview + DexaScanDetailRoute + UploadDexaRoute (dialog) are
        // registered directly here so deep-links to a scan from elsewhere
        // (notifications, dashboard hero tap) land on the right screen.
        composable<Route.Body> {
            BodyCompositionScreen(
                onBack = { navController.popBackStack() },
                onScanClick = { id -> navController.navigate(DexaScanDetailRoute(id)) },
                onUploadClick = { navController.navigate(UploadDexaRoute) },
            )
        }
        composable<DexaScanDetailRoute> {
            DexaScanDetailScreen(onBack = { navController.popBackStack() })
        }
        dialog<UploadDexaRoute> {
            UploadDexaScreen(
                onComplete = { scanId ->
                    navController.popBackStack()
                    navController.navigate(DexaScanDetailRoute(scanId))
                },
                onDismiss = { navController.popBackStack() },
            )
        }

        // IMPL-AND-04: feature-blood replaces the placeholder. The bloodGraph
        // helper would register routes the dialog-shape (AddReading, Upload)
        // wants too, but the Route-typed marker/report-detail destinations
        // need to live in this NavHost so deep-links from Route.BloodReportDetail
        // route through the same back stack. We register both shapes here.
        composable<Route.Blood> {
            BloodOverviewScreen(
                onMarkerClick = { key -> navController.navigate(MarkerDetailRoute(key)) },
                onReportClick = { id -> navController.navigate(ReportDetailRoute(id)) },
                onAddReading = { navController.navigate(AddReadingRoute) },
                onUploadPdf = { navController.navigate(UploadReportRoute) },
            )
        }
        composable<MarkerDetailRoute> {
            MarkerDetailScreen(onBack = { navController.popBackStack() })
        }
        composable<ReportDetailRoute> {
            ReportDetailScreen(onBack = { navController.popBackStack() })
        }
        dialog<AddReadingRoute> {
            AddReadingScreen(
                onDone = { navController.popBackStack() },
                onDismiss = { navController.popBackStack() },
            )
        }
        dialog<UploadReportRoute> {
            UploadLabReportScreen(
                onComplete = { reportId ->
                    navController.popBackStack()
                    navController.navigate(ReportDetailRoute(reportId))
                },
                onDismiss = { navController.popBackStack() },
            )
        }

        // IMPL-AND-06: feature-workouts replaces the placeholder. The
        // GymsList -> NewGym / GymDetail -> EditGym chain plus the
        // bottom-sheet sub-routes for adding equipment and editing a
        // per-location spec override are all registered here so deep
        // links land on the right screen.
        composable<Route.Workouts> {
            GymsListScreen(
                onAddGym = { navController.navigate(NewGymRoute) },
                onOpenGym = { id -> navController.navigate(GymDetailRoute(id)) },
            )
        }
        composable<GymsListRoute> {
            GymsListScreen(
                onAddGym = { navController.navigate(NewGymRoute) },
                onOpenGym = { id -> navController.navigate(GymDetailRoute(id)) },
            )
        }
        composable<NewGymRoute> {
            NewGymScreen(
                onDone = { locationId ->
                    navController.popBackStack()
                    navController.navigate(GymDetailRoute(locationId))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<GymDetailRoute> { entry ->
            val args = entry.toRoute<GymDetailRoute>()
            // Sheet state is held at the NavHost level so the bottom-
            // sheets re-compose into the same back-stack entry rather
            // than producing their own destinations.
            var addEquipmentOpen by remember { mutableStateOf(false) }
            var overrideEquipmentId by remember { mutableStateOf<String?>(null) }
            GymDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { navController.navigate(EditGymRoute(args.locationId)) },
                onAddEquipment = { addEquipmentOpen = true },
                onOpenOverride = { eqId -> overrideEquipmentId = eqId },
                onDeleted = { navController.popBackStack() },
            )
            if (addEquipmentOpen) {
                AddEquipmentSheet(
                    locationId = args.locationId,
                    onDismiss = { addEquipmentOpen = false },
                )
            }
            overrideEquipmentId?.let { eqId ->
                EquipmentOverrideSheet(
                    locationId = args.locationId,
                    equipmentId = eqId,
                    onDismiss = { overrideEquipmentId = null },
                )
            }
        }
        composable<EditGymRoute> {
            EditGymScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

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

        // IMPL-AND-05: Route.DexaDetail kept as a thin redirect to the
        // feature-owned DexaScanDetailRoute so any pre-existing deep links
        // land on the new screen. The feature's route shape is the source
        // of truth going forward.
        composable<Route.DexaDetail> { entry ->
            val args = entry.toRoute<Route.DexaDetail>()
            androidx.compose.runtime.LaunchedEffect(args.scanId) {
                navController.popBackStack()
                navController.navigate(DexaScanDetailRoute(args.scanId))
            }
        }
        // IMPL-AND-04: Route.BloodReportDetail kept as a thin redirect to
        // the feature-owned ReportDetailRoute so any pre-existing deep
        // links land on the new screen. The feature's route shape is the
        // source of truth going forward.
        composable<Route.BloodReportDetail> { entry ->
            val args = entry.toRoute<Route.BloodReportDetail>()
            androidx.compose.runtime.LaunchedEffect(args.reportId) {
                navController.popBackStack()
                navController.navigate(ReportDetailRoute(args.reportId))
            }
        }
        // Route.GymDetail kept as a thin redirect to the feature-owned
        // GymDetailRoute so any pre-existing deep links land on the
        // new screen. The feature's route shape is now the source of
        // truth.
        composable<Route.GymDetail> { entry ->
            val args = entry.toRoute<Route.GymDetail>()
            LaunchedEffect(args.gymId) {
                navController.popBackStack()
                navController.navigate(GymDetailRoute(args.gymId))
            }
        }
    }
}
