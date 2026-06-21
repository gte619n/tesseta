package com.gte619n.healthfitness.feature.medical.nav

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.gte619n.healthfitness.feature.medical.add.AddMedicationScreen
import com.gte619n.healthfitness.feature.medical.detail.MedicationDetailScreen
import com.gte619n.healthfitness.feature.medical.list.MedicationsListScreen
import com.gte619n.healthfitness.feature.medical.reminders.ReminderSettingsScreen
import com.gte619n.healthfitness.feature.medical.today.TodaysDosesScreen

/**
 * String-based Navigation-Compose routes for the medications feature. Mirrors
 * the convention used elsewhere in the app (no @Serializable typed nav).
 */
object MedicationRoutes {
    const val LIST = "medications"
    const val ADD = "medications/add"
    const val REMINDERS = "medications/reminders"

    /** Today's-doses checklist (the medication-reminder notification target). */
    const val TODAY = "medications/today"

    /** Build a concrete detail route for [id]. */
    fun detail(id: String): String = "medications/$id"

    /** Route pattern with the `{medicationId}` placeholder for registration. */
    const val DETAIL = "medications/{medicationId}"

    const val ARG_MEDICATION_ID = "medicationId"

    /**
     * IMPL-STAB Workstream F (item 4): deep-link URI a reminder notification taps
     * into, landing on the [TODAY] dose checklist (per-med checkboxes) instead of
     * app home. The matching `<intent-filter>` lives on `MainActivity`.
     */
    const val DEEP_LINK_DOSE_CHECKLIST = "healthfitness://medications/today"
}

/**
 * Registers all medications destinations on the host graph. The caller is
 * responsible for adding this to its `NavHost { ... }` and supplying the
 * [navController]. The detail/add routes share the same back-stack as the list.
 */
fun NavGraphBuilder.medicationsGraph(navController: NavHostController) {
    composable(route = MedicationRoutes.LIST) {
        MedicationsListScreen(
            onAdd = { navController.navigate(MedicationRoutes.ADD) },
            onMedicationClick = { id -> navController.navigate(MedicationRoutes.detail(id)) },
            onOpenReminders = { navController.navigate(MedicationRoutes.REMINDERS) },
            onBack = { navController.popBackStack() },
        )
    }

    // Exact-string route (preferred over the `{medicationId}` pattern, like
    // REMINDERS below). The medication-reminder notification deep-links here so
    // the user lands on the per-med dose checklist, not the management list.
    composable(
        route = MedicationRoutes.TODAY,
        deepLinks = listOf(navDeepLink { uriPattern = MedicationRoutes.DEEP_LINK_DOSE_CHECKLIST }),
    ) {
        TodaysDosesScreen(
            onBack = { navController.popBackStack() },
            onSeeAll = { navController.navigate(MedicationRoutes.LIST) },
        )
    }

    composable(MedicationRoutes.ADD) {
        AddMedicationScreen(
            onDone = { navController.popBackStack() },
            onBack = { navController.popBackStack() },
        )
    }

    // Exact-string route: Navigation-Compose prefers it over the
    // `{medicationId}` pattern, so "medications/reminders" never falls into
    // the detail destination.
    composable(MedicationRoutes.REMINDERS) {
        ReminderSettingsScreen(
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = MedicationRoutes.DETAIL,
        arguments = listOf(
            navArgument(MedicationRoutes.ARG_MEDICATION_ID) { type = NavType.StringType },
        ),
    ) {
        MedicationDetailScreen(
            onBack = { navController.popBackStack() },
            onDeleted = {
                // Pop back to the list after delete.
                navController.popBackStack(MedicationRoutes.LIST, inclusive = false)
            },
        )
    }
}
