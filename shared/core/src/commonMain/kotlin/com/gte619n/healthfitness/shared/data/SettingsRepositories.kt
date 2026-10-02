package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.presentation.settings.DrinkProposal
import com.gte619n.healthfitness.shared.presentation.settings.Food
import com.gte619n.healthfitness.shared.domain.prefs.CoachAudioSettings
import com.gte619n.healthfitness.shared.domain.prefs.HeightUnit
import com.gte619n.healthfitness.shared.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.shared.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import com.gte619n.healthfitness.shared.domain.profile.Profile
import com.gte619n.healthfitness.shared.domain.withings.WithingsStatus
import kotlinx.coroutines.flow.Flow

/**
 * IMPL-IOS-01 Phase 3 Wave A2 (Settings) — repository interfaces the shared
 * Settings ViewModels depend on. KMP ports of the Android `data.*` contracts:
 *
 *  - [ProfileRepository] mirrors `data.profile.ProfileRepository` (offline-first
 *    singleton profile: `cached()` seeds instantly from the mirror, `get()`
 *    revalidates, the `update*` calls are optimistic).
 *  - [UnitPreferencesRepository] / [CoachAudioPreferences] are on-device DataStore
 *    reads exposed as reactive flows.
 *  - [WorkoutSettingsRepository] is the synced free-text standing instructions.
 *  - [DrinkRepository] backs Settings › Drinks (the management surface — list /
 *    analyze / create / update / regenerate / reorder / archive).
 *  - [WithingsRepository] + [WithingsOAuthCoordinator] back the Withings browser
 *    OAuth connect (healthfitness://withings-callback), identical to Android.
 *  - [AuthRepository] backs sign-out; [AppInfo] carries the build version.
 *
 * Field names + method signatures are 1:1 with the Android sources so the shared
 * ViewModels port with no logic drift. Concrete implementations (Room/DataStore
 * reads + Ktor writes) are the Phase 1C body; a feature can be authored and
 * commonTest-ed against a fake repo before the store lands.
 */

interface ProfileRepository {
    /** Mirror-only read (never network) to seed the screen instantly; null pre-first-sync. */
    suspend fun cached(): Profile?
    /** Serve the mirror first; fill from network only when empty. */
    suspend fun get(): Result<Profile>
    suspend fun updateHeightCm(heightCm: Int?): Result<Profile>
    suspend fun updateBiologicalSex(biologicalSex: String?): Result<Profile>
    suspend fun updateDateOfBirth(dateOfBirth: String?): Result<Profile>
}

interface UnitPreferencesRepository {
    val preferences: Flow<UnitPreferences>
    suspend fun setHeightUnit(unit: HeightUnit)
    suspend fun setWeightUnit(unit: WeightUnit)
    suspend fun setTemperatureUnit(unit: TemperatureUnit)
}

interface CoachAudioPreferences {
    val settings: Flow<CoachAudioSettings>
    suspend fun setRestBeep(enabled: Boolean)
    suspend fun setVoiceAnnouncements(enabled: Boolean)
}

interface WorkoutSettingsRepository {
    /** Reactive cache of the stored standing instructions ("" when unset). */
    val preferences: Flow<String>
    /** Pull the authoritative value from the backend into the cache. */
    suspend fun refresh()
    suspend fun setPreferences(text: String)
}

interface DrinkRepository {
    /** Live list of my drinks from the server (also re-warms the cache); throws on failure. */
    suspend fun listMyDrinks(): List<Food>
    suspend fun analyze(name: String): AnalyzeResult
    suspend fun createDrink(
        name: String,
        abvPercent: Double,
        servingVolumeMl: Double,
        servingLabel: String? = null,
        macros: Macros? = null,
    ): Food
    suspend fun updateDrink(
        id: String,
        name: String,
        abvPercent: Double,
        servingVolumeMl: Double,
        servingLabel: String? = null,
        macros: Macros? = null,
    ): Food
    suspend fun regenerateImage(id: String): Food
    suspend fun reorder(orderedIds: List<String>)
    suspend fun archiveDrink(id: String)

    /** Outcome of [analyze] — separates the 422 "AI unavailable" fallback path. */
    sealed interface AnalyzeResult {
        data class Success(val proposal: DrinkProposal) : AnalyzeResult
        data object Unavailable : AnalyzeResult
        data class Error(val cause: Throwable) : AnalyzeResult
    }
}

interface WithingsRepository {
    suspend fun status(): Result<WithingsStatus>
    /** Cheap connectivity probe; falls back to [status] on the ViewModel side. */
    suspend fun check(): Result<WithingsStatus>
    suspend fun connect(code: String, redirectUri: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}

/** Result of the Withings browser redirect (healthfitness://withings-callback). */
data class WithingsCallback(val code: String?, val state: String?, val error: String?)

/**
 * Relays the Withings OAuth redirect from the platform (which receives the deep
 * link) to the ViewModel, and holds the CSRF `state` across the browser round-trip.
 * Mirrors `data.withings.WithingsOAuthCoordinator`; [buildAuthorizeUrl] is a pure
 * string builder so it's identical on both clients and unit-testable.
 */
interface WithingsOAuthCoordinator {
    val callbacks: Flow<WithingsCallback>
    fun rememberState(state: String)
    fun consumeState(): String?

    companion object {
        const val SCHEME = "healthfitness"
        const val HOST = "withings-callback"
        const val REDIRECT_URI = "$SCHEME://$HOST"
        const val AUTHORIZE_URL = "https://account.withings.com/oauth2_user/authorize2"

        // Sleep Analyzer data needs user.activity; weight/body needs user.metrics.
        const val SCOPE = "user.metrics,user.activity"

        /** Pure string builder (no platform URL types) so it's shared + unit-testable. */
        fun buildAuthorizeUrl(clientId: String, state: String): String {
            fun enc(v: String) = urlEncode(v)
            return AUTHORIZE_URL +
                "?response_type=code" +
                "&client_id=" + enc(clientId) +
                "&scope=" + enc(SCOPE) +
                "&redirect_uri=" + enc(REDIRECT_URI) +
                "&state=" + enc(state)
        }
    }
}

interface AuthRepository {
    suspend fun signOut()
}

/** Build/version info surfaced in Settings › About (parity with Android AppVersionInfo). */
data class AppInfo(
    val versionName: String,
    val versionCode: Int,
)

/**
 * Minimal RFC-3986 form-component percent-encoder — mirrors what
 * `java.net.URLEncoder.encode(v, "UTF-8")` produces for the characters that
 * appear in a Withings authorize URL (the scopes' comma, the redirect URI's
 * `://`). Kept in commonMain so [WithingsOAuthCoordinator.buildAuthorizeUrl] is
 * platform-agnostic.
 */
internal fun urlEncode(value: String): String {
    val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.*"
    val sb = StringBuilder()
    for (ch in value) {
        when {
            ch in allowed -> sb.append(ch)
            ch == ' ' -> sb.append('+')
            else -> for (b in ch.toString().encodeToByteArray()) {
                sb.append('%')
                sb.append(((b.toInt() and 0xFF) shr 4).toHexDigit())
                sb.append((b.toInt() and 0x0F).toHexDigit())
            }
        }
    }
    return sb.toString()
}

private fun Int.toHexDigit(): Char =
    if (this < 10) ('0' + this) else ('A' + (this - 10))
