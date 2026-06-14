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
import com.gte619n.healthfitness.feature.workouts.program.ProgramDetailRoute
import com.gte619n.healthfitness.feature.workouts.program.ProgramsListRoute
import com.gte619n.healthfitness.feature.workouts.program.WorkoutDetailRoute
import com.gte619n.healthfitness.feature.workouts.program.WorkoutsHubRoute
import com.gte619n.healthfitness.feature.workouts.session.WorkoutSessionRoute

/**
 * String-based Navigation-Compose routes for the workouts area. The Workouts
 * destination is a hub (IMPL-AND-15): [HUB] renders [WorkoutsHubScreen] with
 * Gyms / Programs cards. The gyms list (IMPL-AND-06) rebased one level down to
 * [GYMS] = "workouts/gyms" (and its detail/edit under it) so it no longer
 * collides with the new "workouts/programs" routes. Programs are read-only.
 *
 * Path args (`locationId`, `programId`) are read back by ViewModels via
 * [androidx.lifecycle.SavedStateHandle].
 */
object WorkoutsRoutes {
    const val HUB = "workouts"

    // Gyms (IMPL-AND-06), rebased under the hub.
    const val GYMS = "workouts/gyms"
    const val NEW_GYM = "workouts/gyms/new"

    const val ARG_LOCATION_ID = "locationId"

    const val DETAIL = "workouts/gyms/{locationId}"
    const val EDIT = "workouts/gyms/{locationId}/edit"

    fun gymDetail(locationId: String): String = "workouts/gyms/$locationId"
    fun editGym(locationId: String): String = "workouts/gyms/$locationId/edit"

    // Programs (IMPL-AND-15, read-only).
    const val PROGRAMS = "workouts/programs"
    const val ARG_PROGRAM_ID = "programId"
    const val PROGRAM_DETAIL = "workouts/programs/{programId}"

    fun programDetail(programId: String): String = "workouts/programs/$programId"

    // A single workout (one day within a phase). phaseId disambiguates because
    // dayId is unique only within its phase's weekly microcycle.
    const val ARG_PHASE_ID = "phaseId"
    const val ARG_DAY_ID = "dayId"
    const val WORKOUT_DETAIL =
        "workouts/programs/{programId}/phases/{phaseId}/days/{dayId}"

    fun workoutDetail(programId: String, phaseId: String, dayId: String): String =
        "workouts/programs/$programId/phases/$phaseId/days/$dayId"

    // Active session logger (ADR-0012 / IMPL-AND-17). Keyed by the same
    // (programId, scheduledId) pair as the local draft, so start and resume are
    // the same destination — the ViewModel's start() resumes an in-flight draft.
    const val ARG_SCHEDULED_ID = "scheduledId"
    const val SESSION = "workouts/programs/{programId}/sessions/{scheduledId}"

    fun session(programId: String, scheduledId: String): String =
        "workouts/programs/$programId/sessions/$scheduledId"
}

fun NavGraphBuilder.workoutsGraph(
    navController: NavHostController,
    onOpenGoal: (String) -> Unit = {},
) {
    composable(WorkoutsRoutes.HUB) {
        WorkoutsHubRoute(
            onBack = { navController.popBackStack() },
            onOpenGyms = { navController.navigate(WorkoutsRoutes.GYMS) },
            onOpenPrograms = { navController.navigate(WorkoutsRoutes.PROGRAMS) },
            onResumeSession = { programId, scheduledId ->
                navController.navigate(WorkoutsRoutes.session(programId, scheduledId))
            },
        )
    }

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

    composable(WorkoutsRoutes.PROGRAMS) {
        ProgramsListRoute(
            onBack = { navController.popBackStack() },
            onOpenProgram = { id -> navController.navigate(WorkoutsRoutes.programDetail(id)) },
        )
    }

    composable(
        route = WorkoutsRoutes.PROGRAM_DETAIL,
        arguments = listOf(navArgument(WorkoutsRoutes.ARG_PROGRAM_ID) { type = NavType.StringType }),
    ) {
        ProgramDetailRoute(
            onBack = { navController.popBackStack() },
            onOpenGoal = onOpenGoal,
            onOpenWorkout = { programId, phaseId, dayId ->
                navController.navigate(WorkoutsRoutes.workoutDetail(programId, phaseId, dayId))
            },
            onOpenSession = { programId, scheduledId ->
                navController.navigate(WorkoutsRoutes.session(programId, scheduledId))
            },
        )
    }

    composable(
        route = WorkoutsRoutes.WORKOUT_DETAIL,
        arguments = listOf(
            navArgument(WorkoutsRoutes.ARG_PROGRAM_ID) { type = NavType.StringType },
            navArgument(WorkoutsRoutes.ARG_PHASE_ID) { type = NavType.StringType },
            navArgument(WorkoutsRoutes.ARG_DAY_ID) { type = NavType.StringType },
        ),
    ) {
        WorkoutDetailRoute(onBack = { navController.popBackStack() })
    }

    composable(
        route = WorkoutsRoutes.SESSION,
        arguments = listOf(
            navArgument(WorkoutsRoutes.ARG_PROGRAM_ID) { type = NavType.StringType },
            navArgument(WorkoutsRoutes.ARG_SCHEDULED_ID) { type = NavType.StringType },
        ),
    ) {
        WorkoutSessionRoute(onClose = { navController.popBackStack() })
    }
}
