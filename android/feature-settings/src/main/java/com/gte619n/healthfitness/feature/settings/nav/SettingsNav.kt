package com.gte619n.healthfitness.feature.settings.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.gte619n.healthfitness.feature.settings.SettingsScreen
import com.gte619n.healthfitness.feature.settings.profile.ProfileScreen

// String-based Navigation-Compose routes for the settings surface.
object SettingsRoutes {
    const val SETTINGS = "settings"
    const val PROFILE = "settings/profile"
}

// Registers the settings destinations. The app wires `onSignedOut` to its
// AuthCoordinator + sign-in route; nothing else here touches app state.
fun NavGraphBuilder.settingsGraph(
    navController: NavController,
    onSignedOut: () -> Unit,
) {
    composable(SettingsRoutes.SETTINGS) {
        SettingsScreen(
            onNavigateBack = { navController.popBackStack() },
            onNavigateToProfile = { navController.navigate(SettingsRoutes.PROFILE) },
            onSignedOut = onSignedOut,
        )
    }
    composable(SettingsRoutes.PROFILE) {
        ProfileScreen(
            onNavigateBack = { navController.popBackStack() },
        )
    }
}
