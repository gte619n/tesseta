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

    /** Wire the shared REST client. `baseUrl` is normalized to end with "/". */
    fun configure(baseUrl: String, tokenProvider: SessionTokenProvider) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        httpClient = ApiClient.create(normalized, tokenProvider)
    }

    private fun client(): HttpClient =
        httpClient ?: error("IosComposition.configure(...) must be called at launch")

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
