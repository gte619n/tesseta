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
import com.gte619n.healthfitness.feature.workouts.session.WorkoutOverviewRoute
import com.gte619n.healthfitness.feature.workouts.session.WorkoutPlayerRoute
import com.gte619n.healthfitness.feature.workouts.session.WorkoutSummaryRoute

/**
 * String-based Navigation-Compose routes for the workouts feature. Covers both
 * the gym/equipment management screens (IMPL-AND-06) and the guided workout
 * session flow — overview → player → summary (IMPL-WORKOUT-001). The
 * session/detail/edit routes carry a path arg read back by ViewModels via
 * [androidx.lifecycle.SavedStateHandle].
 */
object WorkoutsRoutes {
    const val GYMS = "workouts"
    const val NEW_GYM = "workouts/new"

    const val ARG_LOCATION_ID = "locationId"

    const val DETAIL = "workouts/{locationId}"
    const val EDIT = "workouts/{locationId}/edit"

    fun gymDetail(locationId: String): String = "workouts/$locationId"
    fun editGym(locationId: String): String = "workouts/$locationId/edit"

    // Guided workout session flow (IMPL-WORKOUT-001).
    const val ARG_SESSION_ID = "sessionId"
    const val SESSION_OVERVIEW = "workouts/session/{sessionId}/overview"
    const val SESSION_PLAYER = "workouts/session/{sessionId}/player"
    const val SESSION_SUMMARY = "workouts/session/{sessionId}/summary"

    fun sessionOverview(sessionId: String): String = "workouts/session/$sessionId/overview"
    fun sessionPlayer(sessionId: String): String = "workouts/session/$sessionId/player"
    fun sessionSummary(sessionId: String): String = "workouts/session/$sessionId/summary"
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

    // ── Guided workout session: overview → player → summary ──────────────────

    composable(
        route = WorkoutsRoutes.SESSION_OVERVIEW,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_SESSION_ID) { type = NavType.StringType }),
    ) {
        WorkoutOverviewRoute(
            onStartWorkout = { id -> navController.navigate(WorkoutsRoutes.sessionPlayer(id)) },
            onBack = { navController.popBackStack() },
        )
    }

    composable(
        route = WorkoutsRoutes.SESSION_PLAYER,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_SESSION_ID) { type = NavType.StringType }),
    ) {
        WorkoutPlayerRoute(
            onFinished = { id ->
                navController.navigate(WorkoutsRoutes.sessionSummary(id)) {
                    popUpTo(WorkoutsRoutes.SESSION_OVERVIEW) { inclusive = true }
                }
            },
            onExit = { navController.popBackStack() },
        )
    }

    composable(
        route = WorkoutsRoutes.SESSION_SUMMARY,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_SESSION_ID) { type = NavType.StringType }),
    ) {
        WorkoutSummaryRoute(
            onDone = { navController.popBackStack(WorkoutsRoutes.GYMS, inclusive = true) },
        )
    }
}
