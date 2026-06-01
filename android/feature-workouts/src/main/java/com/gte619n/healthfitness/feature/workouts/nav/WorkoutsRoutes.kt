package com.gte619n.healthfitness.feature.workouts.nav

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.gte619n.healthfitness.feature.workouts.EditGymScreen
import com.gte619n.healthfitness.feature.workouts.GymDetailScreen
import com.gte619n.healthfitness.feature.workouts.GymsListScreen
import com.gte619n.healthfitness.feature.workouts.NewGymScreen

/**
 * String-based Navigation-Compose routes for the workouts (gym/equipment)
 * feature. The detail/edit routes carry a `locationId` path arg, read back
 * by ViewModels via [androidx.lifecycle.SavedStateHandle].
 */
object WorkoutsRoutes {
    const val GYMS = "workouts"
    const val NEW_GYM = "workouts/new"

    const val ARG_LOCATION_ID = "locationId"

    const val DETAIL = "workouts/{locationId}"
    const val EDIT = "workouts/{locationId}/edit"

    fun gymDetail(locationId: String): String = "workouts/$locationId"
    fun editGym(locationId: String): String = "workouts/$locationId/edit"
}

fun NavGraphBuilder.workoutsGraph(navController: NavHostController) {
    composable(WorkoutsRoutes.GYMS) {
        GymsListScreen(
            onBack = { navController.popBackStack() },
            onAddGym = { navController.navigate(WorkoutsRoutes.NEW_GYM) },
            onOpenGym = { id -> navController.navigate(WorkoutsRoutes.gymDetail(id)) },
        )
    }

    composable(WorkoutsRoutes.NEW_GYM) {
        NewGymScreen(
            onBack = { navController.popBackStack() },
            onCreated = { id ->
                navController.navigate(WorkoutsRoutes.gymDetail(id)) {
                    popUpTo(WorkoutsRoutes.GYMS)
                }
            },
        )
    }

    composable(
        route = WorkoutsRoutes.DETAIL,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_LOCATION_ID) { type = NavType.StringType }),
    ) {
        GymDetailScreen(
            onBack = { navController.popBackStack() },
            onEdit = { id -> navController.navigate(WorkoutsRoutes.editGym(id)) },
            onDeleted = { navController.popBackStack(WorkoutsRoutes.GYMS, inclusive = false) },
        )
    }

    composable(
        route = WorkoutsRoutes.EDIT,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_LOCATION_ID) { type = NavType.StringType }),
    ) {
        EditGymScreen(
            onBack = { navController.popBackStack() },
            onSaved = { navController.popBackStack() },
        )
    }
}
