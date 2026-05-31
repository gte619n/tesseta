package com.gte619n.healthfitness.mobile.nav

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gte619n.healthfitness.feature.goals.GOAL_ID_ARG
import com.gte619n.healthfitness.feature.goals.GoalRoadmapRoute
import com.gte619n.healthfitness.feature.goals.GoalsChatRoute
import com.gte619n.healthfitness.feature.goals.GoalsListRoute
import com.gte619n.healthfitness.feature.nutrition.NutritionCaptureRoute
import com.gte619n.healthfitness.feature.nutrition.NutritionTargetRoute
import com.gte619n.healthfitness.feature.nutrition.NutritionTodayRoute
import com.gte619n.healthfitness.mobile.DashboardRoot

// Minimal app NavHost (IMPL-12 assumption 15). The existing dashboard screens
// remain the start destination as static composables; Goals adds two routes.
object Routes {
    const val DASHBOARD = "dashboard"
    const val GOALS_LIST = "goals"
    const val GOALS_CHAT = "goals/chat"
    const val GOAL_DETAIL = "goals/{$GOAL_ID_ARG}"
    fun goalDetail(goalId: String) = "goals/$goalId"

    const val NUTRITION = "nutrition"
    const val NUTRITION_TARGET = "nutrition/target"
    const val NUTRITION_CAPTURE = "nutrition/capture"
}

@Composable
fun AppNavHost(widthClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {
        composable(Routes.DASHBOARD) {
            DashboardRoot(
                widthClass = widthClass,
                onOpenGoals = { navController.navigate(Routes.GOALS_LIST) },
                onOpenNutrition = { navController.navigate(Routes.NUTRITION) },
            )
        }
        composable(Routes.GOALS_LIST) {
            GoalsListRoute(
                onOpenGoal = { goalId -> navController.navigate(Routes.goalDetail(goalId)) },
                onNewGoal = { navController.navigate(Routes.GOALS_CHAT) },
                onBack = { navController.popBackStack() },
            )
        }
        // Registered BEFORE the parameterized goals/{goalId} route so the
        // static "goals/chat" path matches the chat screen, not the detail.
        composable(Routes.GOALS_CHAT) {
            GoalsChatRoute(
                onBack = { navController.popBackStack() },
                onOpenGoal = { goalId ->
                    // Replace the chat in the back stack with the new roadmap.
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

        // IMPL-13 nutrition. Static "nutrition/target" + "nutrition/capture"
        // routes; nutrition has no parameterized path so ordering is moot.
        composable(Routes.NUTRITION) {
            NutritionTodayRoute(
                onOpenTarget = { navController.navigate(Routes.NUTRITION_TARGET) },
                onOpenCapture = { navController.navigate(Routes.NUTRITION_CAPTURE) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.NUTRITION_TARGET) {
            NutritionTargetRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.NUTRITION_CAPTURE) {
            NutritionCaptureRoute(onBack = { navController.popBackStack() })
        }
    }
}
