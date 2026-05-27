package com.gte619n.healthfitness.feature.medical.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.gte619n.healthfitness.feature.medical.add.AddMedicationScreen
import com.gte619n.healthfitness.feature.medical.detail.MedicationDetailScreen
import com.gte619n.healthfitness.feature.medical.list.MedicationsListScreen
import kotlinx.serialization.Serializable

/**
 * Type-safe nav routes for the medications feature. Mirrors the
 * IMPL-AND-00 pattern (`@Serializable` data objects / data classes the
 * `NavHost` round-trips without a manual `NavType` adapter).
 *
 * Plugged into the phone app's `AppNavHost` via [medicationsGraph].
 */
@Serializable
data object MedicationsRoute

@Serializable
data object AddMedicationRoute

@Serializable
data class MedicationDetailRoute(val medicationId: String)

fun NavGraphBuilder.medicationsGraph(
    onBack: () -> Unit,
    navigateToDetail: (medicationId: String) -> Unit,
    navigateToAdd: () -> Unit,
) {
    composable<MedicationsRoute> {
        MedicationsListScreen(
            onAdd = navigateToAdd,
            onMedicationClick = navigateToDetail,
        )
    }
    composable<AddMedicationRoute> {
        AddMedicationScreen(onDone = onBack, onBack = onBack)
    }
    composable<MedicationDetailRoute> {
        MedicationDetailScreen(onBack = onBack)
    }
}
