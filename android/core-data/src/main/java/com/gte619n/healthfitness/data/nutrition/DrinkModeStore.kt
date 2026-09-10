package com.gte619n.healthfitness.data.nutrition

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.domain.nutrition.DrinkSession
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.drinkStore by preferencesDataStore("hf-drink-mode")

/**
 * IMPL-DRINK-01 (D5/D6/D7/D21) — device-local, durable store for Drink Mode and
 * the active [DrinkSession]. Both are NON-SYNCED (D7): they live only here and
 * survive process death / reboot (DataStore persists to disk).
 *
 *  - **Drink Mode** ([enabled]) is a plain boolean toggled from the Nutrition ⋮
 *    menu — it controls whether the home card is present at all.
 *  - The **session** ([session]) lives inside the mode. The session's logged-drink
 *    tally is appended/popped as the user taps tiles / hits Undo; this is the
 *    instant, offline local state (the durable nutrition entries ride the op rail
 *    separately).
 *
 * Invariant (D7): disabling Drink Mode silently ends any active session, so a
 * session can never outlive its card — enforced by [setEnabled].
 */
@Singleton
class DrinkModeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {
    private val sessionAdapter = moshi.adapter(DrinkSession::class.java)

    private val keyEnabled = booleanPreferencesKey("drink_mode_enabled")
    private val keySession = stringPreferencesKey("drink_session_json")

    /** Whether Drink Mode is on (the card is present). */
    val enabled: Flow<Boolean> = context.drinkStore.data.map { it[keyEnabled] ?: false }

    /** The persisted session (defaults to an inactive [DrinkSession.NONE]). */
    val session: Flow<DrinkSession> = context.drinkStore.data.map { prefs ->
        prefs[keySession]?.let { json ->
            runCatching { sessionAdapter.fromJson(json) }.getOrNull()
        } ?: DrinkSession.NONE
    }

    /**
     * Toggle Drink Mode. Turning it OFF while a session is active ends the session
     * silently (no summary) per D7 — clears the session so a card can never be
     * removed while a night is still open.
     */
    suspend fun setEnabled(value: Boolean) {
        context.drinkStore.edit { prefs ->
            prefs[keyEnabled] = value
            if (!value) prefs[keySession] = sessionAdapter.toJson(DrinkSession.NONE)
        }
    }

    /** Start a night: capture start time + [sessionDay] (device-local start day). */
    suspend fun startSession(startedAtMillis: Long, sessionDay: String) {
        writeSession(
            DrinkSession(
                active = true,
                startedAtMillis = startedAtMillis,
                sessionDay = sessionDay,
                loggedDrinks = emptyList(),
            ),
        )
    }

    /** Clear the session back to the empty state (End / silent end). Mode stays as-is. */
    suspend fun clearSession() {
        writeSession(DrinkSession.NONE)
    }

    /** Append a logged drink to the active session's tally (no-op if inactive). */
    suspend fun appendDrink(drink: DrinkSession.LoggedDrink) {
        val current = session.first()
        if (!current.active) return
        writeSession(current.copy(loggedDrinks = current.loggedDrinks + drink))
    }

    /**
     * Pop a logged drink by its [entryId] (Undo). Removes at most one matching row;
     * a no-op if the session already cleared or the row was never there.
     */
    suspend fun removeDrink(entryId: String) {
        val current = session.first()
        val idx = current.loggedDrinks.indexOfLast { it.entryId == entryId }
        if (idx < 0) return
        val updated = current.loggedDrinks.toMutableList().apply { removeAt(idx) }
        writeSession(current.copy(loggedDrinks = updated))
    }

    private suspend fun writeSession(value: DrinkSession) {
        context.drinkStore.edit { it[keySession] = sessionAdapter.toJson(value) }
    }
}
