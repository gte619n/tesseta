package com.gte619n.healthfitness.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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

// offline-fix: upper bound on how long the cold-start splash is held waiting for
// the cached auth session to resolve. The common (returning-user) path resolves in
// a few ms; this only bounds the rare blocking silent-refresh path so the splash
// can never appear stuck.
private const val MAX_SPLASH_HOLD_MS = 1500L

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Application-scoped auth state owner (Hilt @Singleton). Survives activity
    // recreation; bootstrapped from HealthFitnessApp.onCreate (offline-fix) so the
    // cached session is usually resolved before the first composition.
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
        // offline-fix: hold the system splash (app icon on the brand canvas) until
        // the cached auth session resolves out of AuthState.Loading. This replaces
        // the launch-time sign-in flash: a returning user sees the splash, then the
        // dashboard — never the SignInScreen. The bootstrap itself is kicked from
        // HealthFitnessApp.onCreate, so it's usually already resolving by now.
        // Capped at MAX_SPLASH_HOLD_MS so a pathologically slow refresh (the rare
        // signed-in-but-no-access-token path) can't wedge the splash on screen.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val splashStart = SystemClock.elapsedRealtime()
        splash.setKeepOnScreenCondition {
            authCoordinator.state.value is AuthState.Loading &&
                SystemClock.elapsedRealtime() - splashStart < MAX_SPLASH_HOLD_MS
        }
        enableEdgeToEdge()
        observeFoldState()

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
            coordinator = coordinator,
            widthClass = widthClass,
            tokenRegistration = tokenRegistration,
            firstSyncGate = firstSyncGate,
            sessionBootstrap = sessionBootstrap,
            sessionLauncher = sessionLauncher,
        )
        // offline-fix: while the cached session resolves, the system splash is still
        // on screen (held in onCreate) — render nothing rather than the SignInScreen
        // so a returning user never sees a sign-in flash. The Surface canvas shows
        // through in the rare case the splash has already timed out.
        AuthState.Loading -> Unit
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
 *
 * offline-fix: the "do we need to gate?" decision now reads a cheap boolean from
 * the auth DataStore ([AuthCoordinator.isFirstSyncComplete]) instead of opening the
 * SQLCipher DB to inspect `sync_state.lastFullSyncAt`. A returning user therefore
 * drops to the dashboard WITHOUT building the encrypted DB on the visible path —
 * that build (and the backfill scheduling) happens lazily in the background. The
 * encrypted DB is only opened on the launch path for a genuine first run (where we
 * gate anyway) or a one-time upgrade from a pre-flag build.
 */
@Composable
private fun SignedInRoot(
    coordinator: AuthCoordinator,
    widthClass: WindowWidthSizeClass,
    tokenRegistration: TokenRegistration,
    firstSyncGate: dagger.Lazy<FirstSyncGate>,
    sessionBootstrap: dagger.Lazy<WorkoutSessionBootstrap>,
    sessionLauncher: dagger.Lazy<WorkoutSessionForegroundLauncher>,
) {
    // gating: null while the (cheap) decision is in flight, true while a genuine
    // first-run sync blocks the UI, false once released to the dashboard. The null
    // window renders nothing (canvas) — NOT the "Setting up…" screen — so a
    // returning user never flashes the first-run copy.
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

        // Cheap, no-SQLCipher flag read decides the gate.
        if (coordinator.isFirstSyncComplete()) {
            // Returning user: release immediately. Build the gate + schedule the
            // background sync OFF the visible path (this is what opens the DB).
            gating = false
            launch(Dispatchers.IO) {
                runCatching { firstSyncGate.get().scheduleBackfill() }
            }
        } else {
            // Flag not set: either a genuine first run, or a one-time upgrade from a
            // pre-flag build that already has data. Resolve it via the gate (which
            // opens the DB — acceptable here: we're either gating anyway, or paying
            // a one-time upgrade cost). Only a true first run shows "Setting up…".
            val gate = withContext(Dispatchers.IO) { firstSyncGate.get() }
            if (withContext(Dispatchers.IO) { gate.needsFirstSync() }) {
                gating = true
                gate.runInitialSync()
            } else {
                gating = false
            }
            gate.markFirstSyncComplete()
            gate.scheduleBackfill()
            gating = false
        }

        // ADR-0012 Decision 4: after the UI is released, finalize/discard any
        // stale workout drafts and register the periodic sweep. Off the main
        // thread — this touches the SQLCipher store.
        withContext(Dispatchers.IO) {
            runCatching { sessionBootstrap.get().onAppStart() }
        }
    }

    when (gating) {
        // Genuine first run only — the bounded initial sync is in flight.
        true -> SettingUpScreen()
        // Released to the app.
        false -> AppNavHost(widthClass)
        // Deciding (cheap flag read / one-time DB probe): render nothing. The
        // Surface canvas shows through; no "Setting up…" copy for returning users.
        null -> Unit
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
