package com.gte619n.healthfitness.data.auth

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// Persists the backend-issued session (ADR-0010): the short-lived access token
// used as the API bearer, plus the long-lived opaque refresh token traded for a
// new access token over plain HTTP (no Credential Manager, no UI).
//
// `idToken`/`expiresAt` hold the ACCESS token and its expiry — the name is kept
// so AuthInterceptor and the rest of the bearer plumbing are unchanged. The
// refresh token is the credential that survives across launches; presence of a
// refresh token is what "signed in" now means.
//
// Token storage in plain DataStore is acceptable: the access token is
// short-lived (~1h) and audience-scoped, and the refresh token is single-use
// (rotated on every refresh) and server-revocable.

private val Context.authStore by preferencesDataStore("hf-auth")

class IdTokenCache(private val context: Context) {

    private val keyIdToken = stringPreferencesKey("id_token")
    private val keyExpiresAt = longPreferencesKey("expires_at")
    private val keyRefreshToken = stringPreferencesKey("refresh_token")
    private val keyRefreshExpiresAt = longPreferencesKey("refresh_expires_at")
    private val keyHasSignedIn = booleanPreferencesKey("has_signed_in")

    // offline-fix: the first-run sync gate flag lives in this (plain, already-open
    // at launch) auth DataStore — NOT in the SQLCipher mirror — so the launch path
    // can decide whether to show the "Setting up…" gate WITHOUT building the
    // encrypted DB (loadLibs + Keystore crypto) on the critical path. Cleared with
    // the rest of the session on sign-out, so a new sign-in re-gates correctly.
    private val keyFirstSyncComplete = booleanPreferencesKey("first_sync_complete")

    suspend fun read(): Snapshot {
        val prefs = context.authStore.data.first()
        return Snapshot(
            idToken = prefs[keyIdToken],
            expiresAtEpochSeconds = prefs[keyExpiresAt] ?: 0L,
            refreshToken = prefs[keyRefreshToken],
            refreshExpiresAtEpochSeconds = prefs[keyRefreshExpiresAt] ?: 0L,
            hasSignedIn = prefs[keyHasSignedIn] ?: false,
        )
    }

    // Writes a full session: the new access token + its expiry and the (rotated)
    // refresh token + its expiry. Called after a successful exchange or refresh.
    suspend fun writeSession(
        accessToken: String,
        accessExpiresAtEpochSeconds: Long,
        refreshToken: String,
        refreshExpiresAtEpochSeconds: Long,
    ) {
        context.authStore.edit { prefs ->
            prefs[keyIdToken] = accessToken
            prefs[keyExpiresAt] = accessExpiresAtEpochSeconds
            prefs[keyRefreshToken] = refreshToken
            prefs[keyRefreshExpiresAt] = refreshExpiresAtEpochSeconds
            prefs[keyHasSignedIn] = true
        }
    }

    suspend fun clear() {
        context.authStore.edit { prefs -> prefs.clear() }
    }

    fun hasSignedInFlow() = context.authStore.data.map { it[keyHasSignedIn] ?: false }

    /**
     * offline-fix: whether the one-time first-run sync has completed for this
     * signed-in session. Read on the launch path (cheap, no SQLCipher) to decide
     * whether the "Setting up…" gate is needed; a returning user reads `true` here
     * and drops straight to the dashboard.
     */
    suspend fun isFirstSyncComplete(): Boolean =
        context.authStore.data.first()[keyFirstSyncComplete] ?: false

    /** Latch the first-sync-complete flag (idempotent). */
    suspend fun setFirstSyncComplete() {
        context.authStore.edit { prefs -> prefs[keyFirstSyncComplete] = true }
    }

    data class Snapshot(
        val idToken: String?,
        val expiresAtEpochSeconds: Long,
        val refreshToken: String?,
        val refreshExpiresAtEpochSeconds: Long,
        val hasSignedIn: Boolean,
    ) {
        // True when the access token is present and not within [skewSeconds] of
        // expiry — i.e. safe to send without refreshing first.
        fun isFresh(skewSeconds: Long = 60): Boolean =
            idToken != null && System.currentTimeMillis() / 1000 < expiresAtEpochSeconds - skewSeconds

        // A refresh token we still believe is valid — the precondition for a
        // silent HTTP refresh. The server is the authority; this only avoids a
        // pointless round-trip when we already know it's expired.
        val hasUsableRefreshToken: Boolean
            get() = refreshToken != null &&
                System.currentTimeMillis() / 1000 < refreshExpiresAtEpochSeconds
    }
}
