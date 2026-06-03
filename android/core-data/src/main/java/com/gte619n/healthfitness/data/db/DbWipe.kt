package com.gte619n.healthfitness.data.db

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 3) — wipes the encrypted offline DB on sign-out (D5).
 *
 * PHI hygiene requires the local store to be destroyed when the user signs out.
 * [wipe] closes any open [HfDatabase] handle, then deletes `hf-offline.db` plus
 * its `-wal`/`-shm`/`-journal` sidecars. It is null-safe: if the DB was never
 * opened this turn we still delete any on-disk files left from a prior session.
 *
 * Wired into `GoogleAuthRepository.signOut()` via the `onSignOut` hook provided
 * in DI (see `SettingsAppModule` / `DbModule`).
 */
@Singleton
class DbWipe @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    // Lazy provider: don't open/build the DB just to wipe it. If Hilt already
    // built a singleton, we get that instance and close it first.
    private val database: Provider<HfDatabase>,
) {
    suspend fun wipe() {
        // Close any open handle so the OS releases file locks before deletion.
        try {
            val db = database.get()
            if (db.isOpen) db.close()
        } catch (_: Throwable) {
            // DB may never have been opened; nothing to close.
        }
        deleteDbFiles()
    }

    private fun deleteDbFiles() {
        val base = context.getDatabasePath(HfDatabase.DB_NAME)
        listOf(
            base,
            File(base.parentFile, "${HfDatabase.DB_NAME}-wal"),
            File(base.parentFile, "${HfDatabase.DB_NAME}-shm"),
            File(base.parentFile, "${HfDatabase.DB_NAME}-journal"),
        ).forEach { f -> if (f.exists()) f.delete() }
    }
}
