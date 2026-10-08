package com.gte619n.healthfitness.shared.sync

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.gte619n.healthfitness.shared.db.MirrorDatabase

/**
 * Apple driver (iOS device/simulator + the macOS native canary): SQLDelight's
 * NativeSqliteDriver over the on-device SQLite file in the app sandbox. PHI columns
 * are app-layer encrypted ([PayloadCipher]); the file also inherits iOS
 * data-protection at rest. [name] lets sign-out/tests use a distinct file.
 */
actual class MirrorDatabaseFactory(private val name: String = "mirror.db") {
    actual fun open(): MirrorDatabase =
        MirrorDatabase(NativeSqliteDriver(MirrorDatabase.Schema, name))
}
