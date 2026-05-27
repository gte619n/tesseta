package com.gte619n.healthfitness.mobile.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.gte619n.healthfitness.ui.snackbar.LocalSnackbarController
import com.gte619n.healthfitness.ui.theme.Hf

/**
 * Top-level scaffold once the user is signed in. Owns the `NavController`
 * and the width-class-driven choice between bottom nav (compact) and
 * foldable sidebar (medium/expanded). Replaces the IMPL-02 ad-hoc
 * `DashboardRoot` `when (widthClass)` block — routing decisions now live
 * exclusively inside the `NavHost`.
 */
@Composable
fun SignedInScaffold(widthClass: WindowWidthSizeClass) {
    val navController = rememberNavController()
    Scaffold(
        snackbarHost = { SnackbarHost(LocalSnackbarController.current.hostState) },
        bottomBar = {
            if (widthClass == WindowWidthSizeClass.Compact) {
                BottomNavBar(navController)
            }
        },
        containerColor = Hf.colors.canvas,
    ) { padding ->
        Row(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(Hf.colors.canvas),
        ) {
            if (widthClass != WindowWidthSizeClass.Compact) {
                FoldableSidebar(navController)
            }
            AppNavHost(
                navController = navController,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
