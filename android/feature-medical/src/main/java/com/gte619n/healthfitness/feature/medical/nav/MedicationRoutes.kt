package com.gte619n.healthfitness.feature.medical.nav

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gte619n.healthfitness.feature.medical.add.AddMedicationScreen
import com.gte619n.healthfitness.feature.medical.detail.MedicationDetailScreen
import com.gte619n.healthfitness.feature.medical.list.MedicationsListScreen
import com.gte619n.healthfitness.feature.medical.reminders.ReminderSettingsScreen

/**
 * String-based Navigation-Compose routes for the medications feature. Mirrors
 * the convention used elsewhere in the app (no @Serializable typed nav).
 */
object MedicationRoutes {
    const val LIST = "medications"
    const val ADD = "medications/add"
    const val REMINDERS = "medications/reminders"

    /** Build a concrete detail route for [id]. */
    fun detail(id: String): String = "medications/$id"

    /** Route pattern with the `{medicationId}` placeholder for registration. */
    const val DETAIL = "medications/{medicationId}"

    const val ARG_MEDICATION_ID = "medicationId"
}

/**
 * Registers all medications destinations on the host graph. The caller is
 * responsible for adding this to its `NavHost { ... }` and supplying the
 * [navController]. The detail/add routes share the same back-stack as the list.
 */
fun NavGraphBuilder.medicationsGraph(navController: NavHostController) {
    composable(MedicationRoutes.LIST) {
        MedicationsListScreen(
            onAdd = { navController.navigate(MedicationRoutes.ADD) },
            onMedicationClick = { id -> navController.navigate(MedicationRoutes.detail(id)) },
            onOpenReminders = { navController.navigate(MedicationRoutes.REMINDERS) },
            onBack = { navController.popBackStack() },
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
