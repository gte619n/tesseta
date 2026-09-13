package com.gte619n.healthfitness.mobile.auth

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers the backend user id (`sub`) of the last account that signed in on
 * this device. Deliberately NOT part of IdTokenCache: the token cache is
 * cleared on sign-out and when a refresh token is definitively rejected, but
 * this record must survive both so the next interactive sign-in can tell
 * whether the local data on disk (Room mirror, caches, prefs) belongs to a
 * *different* account and must be wiped first (AuthCoordinator).
 *
 * Plain SharedPreferences, not DataStore: a single non-PHI string read
 * synchronously-cheap on the sign-in path.
 */
@Singleton
class LastAccountStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("hf-last-account", Context.MODE_PRIVATE)

    fun read(): String? = prefs.getString(KEY_USER_ID, null)

    fun write(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    private companion object {
        const val KEY_USER_ID = "user_id"
    }
}
