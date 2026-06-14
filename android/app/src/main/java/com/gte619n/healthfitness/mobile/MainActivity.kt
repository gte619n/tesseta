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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionBootstrap
import com.gte619n.healthfitness.mobile.auth.AuthCoordinator
import com.gte619n.healthfitness.mobile.auth.SignInScreen
import com.gte619n.healthfitness.mobile.dashboard.FoldableDashboardScreen
import com.gte619n.healthfitness.mobile.dashboard.PhoneTodayScreen
import com.gte619n.healthfitness.mobile.nav.AppNavHost
import com.gte619n.healthfitness.mobile.push.TokenRegistration
import com.gte619n.healthfitness.mobile.sync.FirstSyncGate
import com.gte619n.healthfitness.mobile.sync.SettingUpScreen
import com.gte619n.healthfitness.mobile.workouts.WorkoutSessionForegroundLauncher
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Application-scoped auth state owner (Hilt @Singleton). Survives activity
    // recreation; bootstrapped below on each onCreate (idempotent).
    @Inject lateinit var authCoordinator: AuthCoordinator

    // IMPL-AND-20 (Phase 6): post-sign-in token registration (D18) + the first-run
    // sync gate (D14). Hilt-injected singletons (Retrofit/WorkManager-backed).
    @Inject lateinit var tokenRegistration: TokenRegistration

    // Lazy on purpose: resolving FirstSyncGate constructs the SQLCipher-backed
    // HfDatabase (loadLibs + Keystore crypto), which must NOT run on the main
    // thread during onCreate injection. SignedInRoot resolves it off the main
    // thread, and only once the user is actually signed in.
    @Inject lateinit var firstSyncGate: dagger.Lazy<FirstSyncGate>

    // ADR-0012 Decision 4: stale workout-draft sweep on app open + periodic
    // worker registration. Lazy for the same SQLCipher-off-main-thread reason.
    @Inject lateinit var sessionBootstrap: dagger.Lazy<WorkoutSessionBootstrap>

    // ADR-0012 Decision 6: keeps the WorkoutSessionService foreground timer
    // alive whenever a local draft session exists (including rehydration after
    // process death). Lazy for the same SQLCipher-off-main-thread reason.
    @Inject lateinit var sessionLauncher: dagger.Lazy<WorkoutSessionForegroundLauncher>

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeFoldState()

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
                        sessionBootstrap = sessionBootstrap,
                        sessionLauncher = sessionLauncher,
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
    firstSyncGate: dagger.Lazy<FirstSyncGate>,
    sessionBootstrap: dagger.Lazy<WorkoutSessionBootstrap>,
    sessionLauncher: dagger.Lazy<WorkoutSessionForegroundLauncher>,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The Activity context — Credential Manager attaches the account picker to it.
    val context = LocalContext.current

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
            sessionBootstrap = sessionBootstrap,
            sessionLauncher = sessionLauncher,
        )
        AuthState.Loading -> SignInScreen(state = state, onSignIn = {})
        else -> SignInScreen(
            state = state,
            onSignIn = { scope.launch { coordinator.interactiveSignIn(context) } },
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
    firstSyncGate: dagger.Lazy<FirstSyncGate>,
    sessionBootstrap: dagger.Lazy<WorkoutSessionBootstrap>,
    sessionLauncher: dagger.Lazy<WorkoutSessionForegroundLauncher>,
) {
    // gateDecided: null until needsFirstSync() resolves; then false (released) or
    // true (blocking initial sync in flight).
    var gating by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        // FCM token registration is best-effort and must NOT block the UI; run it
        // off to the side. (HfMessagingService.onNewToken also re-registers.)
        launch { tokenRegistration.register() }

        // ADR-0012 Decision 6: for the whole signed-in lifetime, start the
        // workout foreground service whenever a local draft exists (the service
        // stops itself when the draft goes away). On IO — resolving the launcher's
        // repository opens the SQLCipher store.
        launch(Dispatchers.IO) { runCatching { sessionLauncher.get().run() } }

        // Resolve the gate (and thus build the SQLCipher DB: loadLibs + Keystore
        // crypto) OFF the main thread — never during onCreate injection.
        val gate = withContext(Dispatchers.IO) { firstSyncGate.get() }

        // A fresh sign-in blocks on a brief, bounded initial sync; a returning
        // user (already has a full sync) is released immediately.
        if (gate.needsFirstSync()) {
            gating = true
            gate.runInitialSync()
        } else {
            gating = false
        }
        // Always schedule the lazy backfill + periodic floor after the gate.
        gate.scheduleBackfill()
        gating = false

        // ADR-0012 Decision 4: after the UI is released, finalize/discard any
        // stale workout drafts and register the periodic sweep. Off the main
        // thread — this touches the SQLCipher store.
        withContext(Dispatchers.IO) {
            runCatching { sessionBootstrap.get().onAppStart() }
        }
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
