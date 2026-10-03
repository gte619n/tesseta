package com.gte619n.healthfitness.shared.ios

import com.gte619n.healthfitness.shared.data.HttpBloodReadingRepository
import com.gte619n.healthfitness.shared.data.HttpBloodTestReportRepository
import com.gte619n.healthfitness.shared.data.HttpBodyCompositionRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardBloodMarkerRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardBodyCompositionRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardDailyMetricsRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardNutritionRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardProfileRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardRecentActivityRepository
import com.gte619n.healthfitness.shared.data.HttpDashboardWorkoutRepository
import com.gte619n.healthfitness.shared.data.HttpDexaScanRepository
import com.gte619n.healthfitness.shared.data.HttpFoodRepository
import com.gte619n.healthfitness.shared.data.HttpGoalsRepository
import com.gte619n.healthfitness.shared.data.HttpNutritionDayRepository
import com.gte619n.healthfitness.shared.data.HttpProfileRepository
import com.gte619n.healthfitness.shared.data.HttpWorkoutProgramRepository
import com.gte619n.healthfitness.shared.data.HttpWorkoutStreakSettingsRepository
import com.gte619n.healthfitness.shared.data.MirrorGoalsRepository
import com.gte619n.healthfitness.shared.data.MirrorMedicationRepository
import com.gte619n.healthfitness.shared.data.MirrorNutritionDayRepository
import com.gte619n.healthfitness.shared.data.MirrorProfileRepository
import com.gte619n.healthfitness.shared.data.MirrorWorkoutSessionRepository
import com.gte619n.healthfitness.shared.data.NoopNutritionOpQueue
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import com.gte619n.healthfitness.shared.presentation.nutrition.AddFoodViewModel
import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsCoachAudioPreferences
import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsUnitPreferencesRepository
import com.gte619n.healthfitness.shared.net.ApiClient
import com.gte619n.healthfitness.shared.net.SessionTokenProvider
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import com.gte619n.healthfitness.shared.sync.KtorSyncApi
import com.gte619n.healthfitness.shared.sync.MirrorDatabaseFactory
import com.gte619n.healthfitness.shared.sync.PayloadCipher
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import com.gte619n.healthfitness.shared.sync.SqlDelightOutboxStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import com.gte619n.healthfitness.shared.sync.SyncEngineImpl
import com.gte619n.healthfitness.shared.db.MirrorDatabase
import com.gte619n.healthfitness.shared.presentation.blood.BloodOverviewViewModel
import com.gte619n.healthfitness.shared.presentation.bodycomposition.BodyCompositionViewModel
import com.gte619n.healthfitness.shared.presentation.dashboard.DashboardViewModel
import com.gte619n.healthfitness.shared.presentation.goals.GoalRoadmapViewModel
import com.gte619n.healthfitness.shared.presentation.goals.GoalsListViewModel
import com.gte619n.healthfitness.shared.presentation.medications.MedicationsViewModel
import com.gte619n.healthfitness.shared.presentation.nutrition.NutritionTargetViewModel
import com.gte619n.healthfitness.shared.presentation.nutrition.NutritionTodayViewModel
import com.gte619n.healthfitness.shared.presentation.settings.CoachAudioSettingsViewModel
import com.gte619n.healthfitness.shared.presentation.settings.ProfileViewModel
import com.gte619n.healthfitness.shared.presentation.settings.UnitsViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.ProgramDetailViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.ProgramsListViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutDetailViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutHistoryViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutSessionViewModel
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutsHubViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 1C — the iOS composition root (DI) + the hand-rolled Flow
 * bridge. SKIE is disabled (incompatible with Xcode 26), so the SwiftUI layer
 * subscribes through [collectFlow] and republishes into `@State`.
 *
 * [configure] is called once at launch from Swift with the backend base URL and
 * a Keychain-backed token provider; networked screens reuse the ONE authenticated
 * Ktor client it builds. On-device-only screens (units) need no client.
 */
object IosComposition {

    private var httpClient: HttpClient? = null
    private val unitPrefs by lazy { NSUserDefaultsUnitPreferencesRepository() }

    // MARK: - Offline-sync graph (Phase E-core)
    //
    // Built ONCE in [configure] and held for the app's lifetime: one SQLDelight
    // mirror DB + its generic mirror/outbox stores + the Ktor sync API + the sync
    // engine. Phases D/E/G consume these via the internal accessors below. The
    // PHI columns are encrypted through the injected [PayloadCipher] (Swift
    // Keychain + CryptoKit on device; NoopPayloadCipher in JVM tests).
    private var mirrorDb: MirrorDatabase? = null
    private var mirrorStoreRef: SqlDelightMirrorStore? = null
    private var outboxStoreRef: SqlDelightOutboxStore? = null
    private var syncEngineRef: SyncEngine? = null
    private var deviceIdRef: String = ""

    /**
     * Wire the shared REST client AND the offline-sync graph. Called once at
     * launch from Swift.
     *
     * @param baseUrl normalized to end with "/".
     * @param tokenProvider Keychain-backed session token source for the Ktor client.
     * @param cipher Keychain/CryptoKit AES-GCM cipher for the mirror's PHI columns.
     * @param deviceId stable per-install UUID (minted + persisted by the Swift
     *   caller in UserDefaults) — sent as `X-HF-Origin-Device` so the backend can
     *   suppress echoing a device's own writes back to it.
     */
    fun configure(
        baseUrl: String,
        tokenProvider: SessionTokenProvider,
        cipher: PayloadCipher,
        deviceId: String,
    ) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val http = ApiClient.create(normalized, tokenProvider)
        httpClient = http

        // Open the mirror DB and build the sync graph once. NativeSqliteDriver is
        // instantiated here for the first time (watch for libsqlite3 at app link).
        val db = MirrorDatabaseFactory().open()
        val mirror = SqlDelightMirrorStore(db, cipher)
        val outbox = SqlDelightOutboxStore(db, cipher)
        val api = KtorSyncApi(http, deviceId)
        mirrorDb = db
        mirrorStoreRef = mirror
        outboxStoreRef = outbox
        syncEngineRef = SyncEngineImpl(api, mirror, outbox)
        deviceIdRef = deviceId
    }

    /**
     * Register (or refresh) this device's push token with the backend (D7):
     * `PUT /api/me/devices/fcm {token, deviceId}`. Called from the Swift push layer
     * when FCM hands up a token. No-op before [configure] / when signed out.
     */
    suspend fun registerPushToken(token: String) {
        val http = httpClient ?: return
        runCatching { http.put("api/me/devices/fcm") { setBody(FcmRegistration(token, deviceIdRef)) } }
    }

    @Serializable
    private data class FcmRegistration(val token: String, val deviceId: String)

    private fun client(): HttpClient =
        httpClient ?: error("IosComposition.configure(...) must be called at launch")

    // MARK: - Offline-sync accessors (consumed by Phases D/E/G)

    /** The generic mirror store (read-through cache). */
    fun mirrorStore(): SqlDelightMirrorStore =
        mirrorStoreRef ?: error("IosComposition.configure(...) must be called at launch")

    /** The durable outbox (local writes survive offline / process death). */
    fun outboxStore(): SqlDelightOutboxStore =
        outboxStoreRef ?: error("IosComposition.configure(...) must be called at launch")

    /** The sync engine (pull loop + outbox drain); drives the first-sync gate. */
    fun syncEngine(): SyncEngine =
        syncEngineRef ?: error("IosComposition.configure(...) must be called at launch")

    /** True once [configure] has built the sync graph (defensive guard for callers
     *  that might fire before launch wiring, e.g. a scenePhase pull). */
    fun isConfigured(): Boolean = syncEngineRef != null

    /**
     * Sign-out teardown (B-6 PHI-leak fix): wipe the on-device mirror rows + cursor
     * and the outbox so a subsequent account can't read the previous user's cached
     * data. The Keychain data key is dropped separately by the Swift auth layer
     * ([KeychainPayloadCipher.wipeKey]), so any residual ciphertext is unreadable
     * even before the rows clear. Safe to call before configure (no-op).
     */
    fun wipeLocalData() {
        mirrorStoreRef?.wipeAllBlocking()
        outboxStoreRef?.clearAllBlocking()
    }

    // MARK: - Screen factories

    private val coachAudioPrefs by lazy { NSUserDefaultsCoachAudioPreferences() }

    /** Settings › Units — on-device unit preferences (no network). */
    fun unitsViewModel(): UnitsViewModel = UnitsViewModel(unitPrefs)

    /** Settings › Coach audio — on-device toggles (no network). */
    fun coachAudioViewModel(): CoachAudioSettingsViewModel =
        CoachAudioSettingsViewModel(coachAudioPrefs)

    /** Settings › Profile — mirror-read (offline/instant) + online PATCH /api/me. */
    fun profileViewModel(): ProfileViewModel =
        ProfileViewModel(MirrorProfileRepository(mirrorStore(), client()), unitPrefs)

    /** Medications list — mirror-read (offline/instant), delta-pull refreshed. */
    fun medicationsViewModel(): MedicationsViewModel =
        MedicationsViewModel(MirrorMedicationRepository(mirrorStore(), syncEngine()))

    /** Goals list — mirror-read (offline/instant); deep goal assembled from the
     *  mirror. Mutations delegate to the networked impl. */
    fun goalsListViewModel(): GoalsListViewModel =
        GoalsListViewModel(MirrorGoalsRepository(HttpGoalsRepository(client()), mirrorStore(), syncEngine()))

    /** Goal roadmap (deep goal — phases + steps). Same mirror-read construction as
     *  the list factory; the VM drives step done-toggle + reset-to-auto (online
     *  PATCH, mirror reconciled on the next pull). */
    fun goalRoadmapViewModel(goalId: String): GoalRoadmapViewModel =
        GoalRoadmapViewModel(
            goalId = goalId,
            repository = MirrorGoalsRepository(HttpGoalsRepository(client()), mirrorStore(), syncEngine()),
        )

    /**
     * Blood / Labs overview — networked over the existing endpoints
     * (`GET/POST/DELETE api/me/blood` + `/reports`). Online-first repos; the
     * tracked-marker derivation + offline-never-blank behaviour live in the shared
     * VM. The lab-PDF upload (multipart SSE) is platform-stubbed (no shared SSE
     * client yet) — the repo emits a graceful Failed.
     */
    fun bloodOverviewViewModel(): BloodOverviewViewModel =
        BloodOverviewViewModel(
            readings = HttpBloodReadingRepository(client()),
            reports = HttpBloodTestReportRepository(client()),
        )

    /**
     * Body-composition overview — networked over `GET api/me/body-composition`
     * (snapshot DERIVED in the repo, porting Android's buildSnapshot) + the DEXA
     * scan list/detail/patch (`api/me/dexa/scans`). Pull-only (no outbox). DEXA PDF
     * upload is platform-stubbed. Weight-unit projection reads the on-device unit
     * prefs (same source as Units/Profile).
     */
    fun bodyCompositionViewModel(): BodyCompositionViewModel =
        BodyCompositionViewModel(
            bodyRepo = HttpBodyCompositionRepository(client()),
            dexaRepo = HttpDexaScanRepository(client()),
            unitPrefsRepo = unitPrefs,
        )

    /**
     * Nutrition Today — networked (GET/POST/PATCH/DELETE api/me/nutrition/…).
     * Online-first: the op rail is inert ([NoopNutritionOpQueue]) until the durable
     * capture/outbox layer lands, so the screen is read + direct entry logging.
     * [initialDate] is today's ISO date, minted by the Swift caller in device tz.
     */
    fun nutritionTodayViewModel(initialDate: String): NutritionTodayViewModel =
        NutritionTodayViewModel(
            // Networked day + an offline read-through cache (cachedDay served from the
            // mirror; day() seeds it). observeDay/mutations stay on the network impl.
            repository = MirrorNutritionDayRepository(HttpNutritionDayRepository(client()), mirrorStore()),
            ops = NoopNutritionOpQueue(),
            initialDate = initialDate,
        )

    /**
     * Nutrition daily macro-target editor (Settings/Nutrition › Daily targets) —
     * networked over `GET/PUT api/me/nutrition/target` via [HttpNutritionDayRepository].
     * The shared [NutritionTargetViewModel] loads the current target and saves an
     * edited one (`saved` drives the one-shot confirmation). Read + save only; no
     * mirror/outbox (a plain online form, same as Android).
     */
    fun nutritionTargetViewModel(): NutritionTargetViewModel =
        NutritionTargetViewModel(repository = HttpNutritionDayRepository(client()))

    /**
     * Add-food sheet — networked catalog + saved-meal search (GET api/foods/search,
     * api/me/nutrition/meals/search) + the one-tap recent-meals list. [mealWire] is
     * the wire name of the meal being logged ("BREAKFAST"…); an unknown value falls
     * back to SNACK. The actual logging goes through the shared Today VM the Swift
     * caller already holds, so a logged entry refreshes the open day.
     */
    fun addFoodViewModel(mealWire: String): AddFoodViewModel =
        AddFoodViewModel(
            foods = HttpFoodRepository(client()),
            nutrition = HttpNutritionDayRepository(client()),
            currentMeal = Meal.entries.firstOrNull { it.wire == mealWire } ?: Meal.SNACK,
        )

    /**
     * Workout session logger — the offline-first live session (ADR-0012 / Phase G).
     * Backed by [MirrorWorkoutSessionRepository] on the shared mirror + outbox rail:
     * every set edit is a LOCAL-ONLY dirty draft write (survives process death /
     * offline), and finish/skip enqueue ONE idempotent completion PUT through the
     * outbox. [programId]/[scheduledId] are the nav args the SwiftUI logger opens on.
     */
    fun workoutSessionViewModel(programId: String, scheduledId: String): WorkoutSessionViewModel =
        WorkoutSessionViewModel(
            repository = MirrorWorkoutSessionRepository(
                mirror = mirrorStore(),
                outbox = outboxStore(),
                engine = syncEngine(),
                client = client(),
            ),
            programId = programId,
            scheduledId = scheduledId,
        )

    // MARK: - Workouts browse (Phase 3 Wave D — iOS wiring)
    //
    // One shared [HttpWorkoutProgramRepository] so the shallow-programs +
    // deep-program caches stay warm across the hub / list / detail screens within a
    // session (each ViewModel is still its own instance; they share the read-through
    // cache). The live-session repo is rebuilt per factory, exactly as
    // [workoutSessionViewModel] constructs it (mirror + outbox + engine + client) —
    // the browse screens only READ drafts/parked completions from it.

    private val workoutProgramRepo by lazy { HttpWorkoutProgramRepository(client()) }
    private val workoutStreakRepo by lazy { HttpWorkoutStreakSettingsRepository(client()) }

    private fun workoutSessionRepository(): MirrorWorkoutSessionRepository =
        MirrorWorkoutSessionRepository(
            mirror = mirrorStore(),
            outbox = outboxStore(),
            engine = syncEngine(),
            client = client(),
        )

    /**
     * Workouts hub — the read-first "This Week" landing (featured program +
     * compliance grid + streak + resume/parked banners). KMP port of Android's
     * `WorkoutsLandingViewModel`. Networked programs/calendar/stats reads; the
     * compliance + streak maths are derived in the shared VM (ComplianceMath).
     */
    fun workoutsHubViewModel(): WorkoutsHubViewModel =
        WorkoutsHubViewModel(
            repository = workoutProgramRepo,
            sessionRepository = workoutSessionRepository(),
            settingsRepository = workoutStreakRepo,
        )

    /** Programs list — reactive shallow programs list (online-first). */
    fun programsListViewModel(): ProgramsListViewModel =
        ProgramsListViewModel(repository = workoutProgramRepo)

    /**
     * One program's detail (deep tree + this-week/past strips + activate / edit /
     * continue / apply-nutrition / delete-session / restore-parked). Keyed by id.
     */
    fun programDetailViewModel(programId: String): ProgramDetailViewModel =
        ProgramDetailViewModel(
            repository = workoutProgramRepo,
            sessionRepository = workoutSessionRepository(),
            programId = programId,
        )

    /**
     * A single workout day, read-only viewer + prior-performance last-sets hint +
     * "run this workout today" (materializes a session dated today).
     */
    fun workoutDetailViewModel(
        programId: String,
        phaseId: String,
        dayId: String,
    ): WorkoutDetailViewModel =
        WorkoutDetailViewModel(
            repository = workoutProgramRepo,
            programId = programId,
            phaseId = phaseId,
            dayId = dayId,
        )

    /** Read-only, paged Workout History (COMPLETED sessions, newest first). */
    fun workoutHistoryViewModel(): WorkoutHistoryViewModel =
        WorkoutHistoryViewModel(
            repository = workoutProgramRepo,
            sessionRepository = workoutSessionRepository(),
        )

    // MARK: - Today dashboard (Phase 3 Wave A1 — iOS wiring)
    //
    // The shared [DashboardViewModel] takes SEVEN dashboard-scoped repositories,
    // each a thin online-first Http impl over the EXISTING backend endpoints the
    // feature screens already use (ports of Android's data.dashboard.*). The
    // workout repo reuses the warm program cache ([workoutProgramRepo]) + a
    // mirror-backed session repo for the reactive draft resume, and the
    // body-composition repo for the completed-session recap bodyweight. No
    // on-device mirror read yet — the `cached*` methods return null/empty and the
    // VM falls through to the network `load*` (deferred mirror-read pass).

    /** Today dashboard — the home screen, bound to the shared [DashboardViewModel]. */
    fun dashboardViewModel(): DashboardViewModel {
        val bodyComp = HttpDashboardBodyCompositionRepository(client())
        return DashboardViewModel(
            bodyComp = bodyComp,
            dailyMetrics = HttpDashboardDailyMetricsRepository(client()),
            blood = HttpDashboardBloodMarkerRepository(client()),
            nutrition = HttpDashboardNutritionRepository(HttpNutritionDayRepository(client())),
            recent = HttpDashboardRecentActivityRepository(client()),
            workouts = HttpDashboardWorkoutRepository(
                programs = workoutProgramRepo,
                sessions = workoutSessionRepository(),
                bodyComp = bodyComp,
            ),
            profile = HttpDashboardProfileRepository(HttpProfileRepository(client())),
        )
    }

    // MARK: - Flow bridge

    /**
     * Subscribe to a Kotlin [Flow] from Swift. Collection runs on the main
     * dispatcher; each emission is handed to [onEach]. The returned
     * [FlowSubscription] is cancelled by the view on disappear. Emissions arrive
     * as the ObjC-bridged element type (the Swift caller casts).
     */
    fun <T : Any> collectFlow(flow: Flow<T>, onEach: (T) -> Unit): FlowSubscription {
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val job: Job = scope.launch { flow.collect { onEach(it) } }
        return FlowSubscription(job)
    }
}

/** Cancellation handle for an [IosComposition.collectFlow] subscription. */
class FlowSubscription internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}
