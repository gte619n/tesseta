package com.gte619n.healthfitness.mobile

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.mobile.auth.AuthUiState
import com.gte619n.healthfitness.mobile.auth.AuthViewModel
import com.gte619n.healthfitness.mobile.auth.SignInScreen
import com.gte619n.healthfitness.mobile.auth.toLegacyAuthState
import com.gte619n.healthfitness.mobile.navigation.SignedInScaffold
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.snackbar.ProvideSnackbarController
import com.gte619n.healthfitness.ui.theme.Hf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Phone entry point. Hilt now owns the auth + token-publish bindings, so
 * the activity body shrinks to: fold-state observation, theme +
 * window-size-class, and the root composable. Routing decisions live in
 * [SignedInScaffold] + [AppNavHost] rather than an `if (state is
 * SignedIn)` block here.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeFoldState()

        setContent {
            HealthFitnessTheme {
                val windowSize = calculateWindowSizeClass(this)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Hf.colors.canvas,
                ) {
                    ProvideSnackbarController {
                        AppRoot(widthClass = windowSize.widthSizeClass)
                    }
                }
            }
        }
    }

    /**
     * Gate rotation by fold state: when a FoldingFeature is present (we're on
     * the inner / unfolded display), allow rotation via the sensor. When there
     * is none (cover display, or non-foldable device), lock to portrait.
     * `FULL_SENSOR` is used rather than `USER` so rotation isn't suppressed by
     * the system rotation-lock setting on devices where that policy applies to
     * the inner display.
     */
    private fun observeFoldState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@MainActivity)
                    .windowLayoutInfo(this@MainActivity)
                    .collect { layoutInfo ->
                        val isUnfolded = layoutInfo.displayFeatures
                            .filterIsInstance<FoldingFeature>()
                            .isNotEmpty()
                        requestedOrientation = if (isUnfolded) {
                            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        }
                    }
            }
        }
    }
}

@Composable
private fun AppRoot(widthClass: WindowWidthSizeClass) {
    val authViewModel: AuthViewModel = hiltViewModel()
    val state by authViewModel.uiState.collectAsState()
    when (state) {
        is AuthUiState.SignedIn -> SignedInScaffold(widthClass = widthClass)
        AuthUiState.Loading -> SignInScreen(state = AuthState.Loading, onSignIn = {})
        else -> SignInScreen(
            state = state.toLegacyAuthState(),
            onSignIn = { authViewModel.interactiveSignIn() },
        )
    }
}
