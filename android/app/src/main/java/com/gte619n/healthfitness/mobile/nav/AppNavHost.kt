package com.gte619n.healthfitness.mobile.nav

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.mobile.sync.SyncStatusViewModel
import com.gte619n.healthfitness.ui.sync.SyncStatusOverlay
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gte619n.healthfitness.feature.blood.nav.BloodRoutes
import com.gte619n.healthfitness.feature.blood.nav.bloodGraph
import com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes
import com.gte619n.healthfitness.feature.bodycomposition.nav.bodyCompositionGraph
import com.gte619n.healthfitness.feature.goals.GOAL_ID_ARG
import com.gte619n.healthfitness.feature.goals.GoalRoadmapRoute
import com.gte619n.healthfitness.feature.goals.GoalsChatRoute
import com.gte619n.healthfitness.feature.goals.GoalsListRoute
import com.gte619n.healthfitness.feature.medical.nav.MedicationRoutes
import com.gte619n.healthfitness.feature.medical.nav.medicationsGraph
import com.gte619n.healthfitness.feature.nutrition.NutritionCaptureRoute
import com.gte619n.healthfitness.feature.nutrition.NutritionTargetRoute
import com.gte619n.healthfitness.feature.nutrition.NutritionTodayRoute
import com.gte619n.healthfitness.feature.settings.nav.SettingsRoutes
import com.gte619n.healthfitness.feature.settings.nav.settingsGraph
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import com.gte619n.healthfitness.feature.workouts.nav.workoutsGraph
import com.gte619n.healthfitness.mobile.DashboardRoot
import com.gte619n.healthfitness.mobile.MoreScreen
import java.time.LocalDate

// App NavHost. The dashboard is the start destination; every parity feature
// (IMPL-AND-02..06, -12) registers its own nested graph here. Destinations are
// reached via the dashboard's `onNavigate(route)` callback (sidebar / bottom
// nav) — the route constants are re-exported below for convenience.
object Routes {
    const val DASHBOARD = "dashboard"
    const val GOALS_LIST = "goals"
    const val GOALS_CHAT = "goals/chat"
    const val GOAL_DETAIL = "goals/{$GOAL_ID_ARG}"
    fun goalDetail(goalId: String) = "goals/$goalId"

    // Feature entry routes (re-exported so dashboard nav items can target them).
    const val MEDICATIONS = MedicationRoutes.LIST
    const val BLOOD = BloodRoutes.OVERVIEW
    const val BODY = BodyCompositionRoutes.BODY
    const val WORKOUTS = WorkoutsRoutes.HUB
    const val SETTINGS = SettingsRoutes.SETTINGS

    const val MORE = "more"

    const val NUTRITION = "nutrition"
    const val NUTRITION_TARGET = "nutrition/target"
    const val NUTRITION_CAPTURE = "nutrition/capture"

    // Capture accepts an optional ?date=YYYY-MM-DD so a meal photo taken while
    // reviewing another day logs to that day. Absent (e.g. the dashboard quick-
    // capture, which navigates to the bare NUTRITION_CAPTURE) → the capture
    // ViewModel defaults to today.
    const val NUTRITION_CAPTURE_ARG_DATE = "date"
    const val NUTRITION_CAPTURE_PATTERN = "nutrition/capture?date={date}"
    fun nutritionCaptureRoute(date: LocalDate? = null): String =
        if (date == null) NUTRITION_CAPTURE else "nutrition/capture?date=$date"
}

@Composable
fun AppNavHost(
    widthClass: WindowWidthSizeClass,
    syncStatusViewModel: SyncStatusViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    // IMPL-AND-20 (Phase 6, D11): the global sync-state indicator is an OVERLAY
    // drawn on top of the nav graph, so it never shifts the layout (no content
    // jump). In the common states (syncing / pending / offline) it's just a faint
    // status icon in the top-right corner; only a FAILED sync surfaces the full
    // banner (with Retry). Steady state shows nothing.
    val syncState by syncStatusViewModel.state.collectAsStateWithLifecycle()

    // IMPL-STAB Workstream F (item 4): NavHost auto-handles the activity's INITIAL
    // intent (cold-start deep link from a reminder notification). When the app is
    // already running, the launch arrives via onNewIntent — singleTop activity —
    // so forward those to the controller too, otherwise a notification tap on a
    // foregrounded app would be a no-op.
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity, navController) {
        val listener = androidx.core.util.Consumer<Intent> { intent ->
            navController.handleDeepLink(intent)
        }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppNavHostGraph(widthClass = widthClass, navController = navController)
        SyncStatusOverlay(state = syncState, onRetry = syncStatusViewModel::retry)
    }
}

@Composable
private fun AppNavHostGraph(
    widthClass: WindowWidthSizeClass,
    navController: androidx.navigation.NavHostController,
) {
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardRoot(
                widthClass = widthClass,
                onOpenGoals = { navController.navigate(Routes.GOALS_LIST) },
                onNavigate = { route -> navController.navigate(route) },
            )
        }
        composable(Routes.GOALS_LIST) {
            GoalsListRoute(
                onOpenGoal = { goalId -> navController.navigate(Routes.goalDetail(goalId)) },
                onNewGoal = { navController.navigate(Routes.GOALS_CHAT) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.GOALS_CHAT) {
            GoalsChatRoute(
                onBack = { navController.popBackStack() },
                onOpenGoal = { goalId ->
                    navController.navigate(Routes.goalDetail(goalId)) {
                        popUpTo(Routes.GOALS_CHAT) { inclusive = true }
                    }
                },
            )
        }
        composable(
            route = Routes.GOAL_DETAIL,
            arguments = listOf(navArgument(GOAL_ID_ARG) { type = NavType.StringType }),
        ) {
            GoalRoadmapRoute(onBack = { navController.popBackStack() })
        }

        // Parity feature graphs (IMPL-AND-02..06).
        medicationsGraph(navController)
        bloodGraph(navController)
        bodyCompositionGraph(navController)
        workoutsGraph(
            navController = navController,
            onOpenGoal = { goalId -> navController.navigate(Routes.goalDetail(goalId)) },
        )
        settingsGraph(
            navController = navController,
            onSignedOut = { navController.popBackStack(Routes.DASHBOARD, inclusive = false) },
        )

        // IMPL-13 nutrition. Static "nutrition/target" + "nutrition/capture"
        // routes; nutrition has no parameterized path so ordering is moot.
        composable(Routes.NUTRITION) {
            NutritionTodayRoute(
                onOpenTarget = { navController.navigate(Routes.NUTRITION_TARGET) },
                onOpenCapture = { date -> navController.navigate(Routes.nutritionCaptureRoute(date)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.NUTRITION_TARGET) {
            NutritionTargetRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.NUTRITION_CAPTURE_PATTERN,
            arguments = listOf(
                navArgument(Routes.NUTRITION_CAPTURE_ARG_DATE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            NutritionCaptureRoute(onBack = { navController.popBackStack() })
        }

        // "More" hub: a phone-only feature directory reachable from the bottom
        // nav, surfacing the parity features that don't have a dedicated tab.
        composable(Routes.MORE) {
            MoreScreen(
                onNavigate = { route -> navController.navigate(route) },
                onBack = { navController.popBackStack() },
            )
        }
    }
}
