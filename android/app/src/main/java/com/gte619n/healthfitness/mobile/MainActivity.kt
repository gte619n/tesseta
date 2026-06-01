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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.mobile.auth.AuthCoordinator
import com.gte619n.healthfitness.mobile.auth.SignInScreen
import com.gte619n.healthfitness.mobile.dashboard.FoldableDashboardScreen
import com.gte619n.healthfitness.mobile.dashboard.PhoneTodayScreen
import com.gte619n.healthfitness.mobile.nav.AppNavHost
import com.gte619n.healthfitness.mobile.wear.PhoneTokenPublisher
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var authCoordinator: AuthCoordinator

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeFoldState()

        val cache = IdTokenCache(applicationContext)
        val publisher = PhoneTokenPublisher(applicationContext)
        val repo = GoogleAuthRepository(
            context = this,
            cache = cache,
            webOauthClientId = BuildConfig.WEB_OAUTH_CLIENT_ID,
            onTokenIssued = { token, _ -> publisher.publish(token) },
        )
        authCoordinator = AuthCoordinator(repo, cache)

        lifecycleScope.launch { authCoordinator.bootstrap() }

        setContent {
            HealthFitnessTheme {
                val windowSize = calculateWindowSizeClass(this)
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Hf.colors.canvas,
                ) {
                    AppRoot(
                        coordinator = authCoordinator,
                        widthClass = windowSize.widthSizeClass,
                    )
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
private fun AppRoot(
    coordinator: AuthCoordinator,
    widthClass: WindowWidthSizeClass,
) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()

    when (state) {
        is AuthState.SignedIn -> AppNavHost(widthClass)
        AuthState.Loading -> SignInScreen(state = state, onSignIn = {})
        else -> SignInScreen(
            state = state,
            onSignIn = { scope.launch { coordinator.interactiveSignIn() } },
        )
    }
}

@Composable
fun DashboardRoot(
    widthClass: WindowWidthSizeClass,
    onOpenGoals: () -> Unit = {},
    onNavigate: (route: String) -> Unit = {},
) {
    // Compact (< 600 dp) → phone Today screen.
    // Medium / Expanded (≥ 600 dp) → foldable/tablet dashboard with icon-only sidebar.
    when (widthClass) {
        WindowWidthSizeClass.Compact -> PhoneTodayScreen(onOpenGoals = onOpenGoals, onNavigate = onNavigate)
        else -> FoldableDashboardScreen(onOpenGoals = onOpenGoals, onNavigate = onNavigate)
    }
}
