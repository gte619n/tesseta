package com.gte619n.healthfitness.shared.ios

import com.gte619n.healthfitness.shared.data.HttpFoodRepository
import com.gte619n.healthfitness.shared.data.HttpGoalsRepository
import com.gte619n.healthfitness.shared.data.HttpMedicationRepository
import com.gte619n.healthfitness.shared.data.HttpNutritionDayRepository
import com.gte619n.healthfitness.shared.data.HttpProfileRepository
import com.gte619n.healthfitness.shared.data.NoopNutritionOpQueue
import com.gte619n.healthfitness.shared.domain.nutrition.Meal
import com.gte619n.healthfitness.shared.presentation.nutrition.AddFoodViewModel
import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsCoachAudioPreferences
import com.gte619n.healthfitness.shared.data.ios.NSUserDefaultsUnitPreferencesRepository
import com.gte619n.healthfitness.shared.net.ApiClient
import com.gte619n.healthfitness.shared.net.SessionTokenProvider
import com.gte619n.healthfitness.shared.sync.KtorSyncApi
import com.gte619n.healthfitness.shared.sync.MirrorDatabaseFactory
import com.gte619n.healthfitness.shared.sync.PayloadCipher
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import com.gte619n.healthfitness.shared.sync.SqlDelightOutboxStore
import com.gte619n.healthfitness.shared.sync.SyncEngine
import com.gte619n.healthfitness.shared.sync.SyncEngineImpl
import com.gte619n.healthfitness.shared.db.MirrorDatabase
import com.gte619n.healthfitness.shared.presentation.goals.GoalsListViewModel
import com.gte619n.healthfitness.shared.presentation.medications.MedicationsViewModel
import com.gte619n.healthfitness.shared.presentation.nutrition.NutritionTodayViewModel
import com.gte619n.healthfitness.shared.presentation.settings.CoachAudioSettingsViewModel
import com.gte619n.healthfitness.shared.presentation.settings.ProfileViewModel
import com.gte619n.healthfitness.shared.presentation.settings.UnitsViewModel
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
    }

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

    // MARK: - Screen factories

    private val coachAudioPrefs by lazy { NSUserDefaultsCoachAudioPreferences() }

    /** Settings › Units — on-device unit preferences (no network). */
    fun unitsViewModel(): UnitsViewModel = UnitsViewModel(unitPrefs)

    /** Settings › Coach audio — on-device toggles (no network). */
    fun coachAudioViewModel(): CoachAudioSettingsViewModel =
        CoachAudioSettingsViewModel(coachAudioPrefs)

    /** Settings › Profile — networked (GET/PATCH /api/me). */
    fun profileViewModel(): ProfileViewModel =
        ProfileViewModel(HttpProfileRepository(client()), unitPrefs)

    /** Medications list — networked (GET /api/me/medications). */
    fun medicationsViewModel(): MedicationsViewModel =
        MedicationsViewModel(HttpMedicationRepository(client()))

    /** Goals list — networked (GET /api/me/goals). */
    fun goalsListViewModel(): GoalsListViewModel =
        GoalsListViewModel(HttpGoalsRepository(client()))

    /**
     * Nutrition Today — networked (GET/POST/PATCH/DELETE api/me/nutrition/…).
     * Online-first: the op rail is inert ([NoopNutritionOpQueue]) until the durable
     * capture/outbox layer lands, so the screen is read + direct entry logging.
     * [initialDate] is today's ISO date, minted by the Swift caller in device tz.
     */
    fun nutritionTodayViewModel(initialDate: String): NutritionTodayViewModel =
        NutritionTodayViewModel(
            repository = HttpNutritionDayRepository(client()),
            ops = NoopNutritionOpQueue(),
            initialDate = initialDate,
        )

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
