package com.gte619n.healthfitness.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.gte619n.healthfitness.mobile.push.TokenRegistration
import com.gte619n.healthfitness.mobile.sync.FirstSyncGate
import com.gte619n.healthfitness.mobile.sync.SettingUpScreen
import com.gte619n.healthfitness.mobile.wear.PhoneTokenPublisher
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var authCoordinator: AuthCoordinator

    // IMPL-AND-20 (Phase 6): post-sign-in token registration (D18) + the first-run
    // sync gate (D14). Hilt-injected singletons (Retrofit/WorkManager-backed).
    @Inject lateinit var tokenRegistration: TokenRegistration

    @Inject lateinit var firstSyncGate: FirstSyncGate

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
                        tokenRegistration = tokenRegistration,
                        firstSyncGate = firstSyncGate,
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
    tokenRegistration: TokenRegistration,
    firstSyncGate: FirstSyncGate,
) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Returning from the system Add-Account screen — re-probe so a freshly
    // added account flips us from NoAccount to the normal sign-in prompt.
    val addAccountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { scope.launch { coordinator.bootstrap() } }

    when (state) {
        is AuthState.SignedIn -> SignedInRoot(
            widthClass = widthClass,
            tokenRegistration = tokenRegistration,
            firstSyncGate = firstSyncGate,
        )
        AuthState.Loading -> SignInScreen(state = state, onSignIn = {})
        else -> SignInScreen(
            state = state,
            onSignIn = { scope.launch { coordinator.interactiveSignIn() } },
            onAddAccount = {
                val intent = Intent(Settings.ACTION_ADD_ACCOUNT)
                    .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                addAccountLauncher.launch(intent)
            },
        )
    }
}

/**
 * IMPL-AND-20 (Phase 6) — the post-sign-in entry. Runs the FCM token
 * registration (D18) and the first-run sync gate (D14) exactly once per sign-in,
 * showing the blocking "Setting up…" screen only for a fresh sign-in; returning
 * users (who already have a full sync) drop straight to the dashboard.
 */
@Composable
private fun SignedInRoot(
    widthClass: WindowWidthSizeClass,
    tokenRegistration: TokenRegistration,
    firstSyncGate: FirstSyncGate,
) {
    // gateDecided: null until needsFirstSync() resolves; then false (released) or
    // true (blocking initial sync in flight).
    var gating by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        // FCM token registration is best-effort and must NOT block the UI; run it
        // off to the side. (HfMessagingService.onNewToken also re-registers.)
        launch { tokenRegistration.register() }

        // A fresh sign-in blocks on a brief, bounded initial sync; a returning
        // user (already has a full sync) is released immediately.
        if (firstSyncGate.needsFirstSync()) {
            gating = true
            firstSyncGate.runInitialSync()
        } else {
            gating = false
        }
        // Always schedule the lazy backfill + periodic floor after the gate.
        firstSyncGate.scheduleBackfill()
        gating = false
    }

    if (gating != false) {
        // null (deciding) or true (gating) — both show the brief setup screen.
        // The decide step is a single fast Room read, so a returning user only
        // flashes this for a frame before dropping to the dashboard.
        SettingUpScreen()
    } else {
        AppNavHost(widthClass)
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
        WindowWidthSizeClass.Compact -> PhoneTodayScreen(onNavigate = onNavigate)
        else -> FoldableDashboardScreen(onOpenGoals = onOpenGoals, onNavigate = onNavigate)
    }
}
