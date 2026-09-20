package com.gte619n.healthfitness.data.workouts.adhoc

import com.gte619n.healthfitness.data.db.dao.AdHocWorkoutDao
import com.gte619n.healthfitness.domain.workouts.adhoc.AdHocLibraryItem
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only, offline-first view of the ad-hoc workout Library (IMPL-ADHOC-01).
 * The phone doesn't create ad-hoc workouts yet (generate + the guided run are
 * the remaining Phase 4 work), so this only reads the {@code adhocWorkouts} Room
 * mirror the background SyncEngine fills — a workout generated on the web shows
 * up here. No network, no outbox: purely a reactive read of the mirror, parsed
 * from each row's {@code payloadJson} and sorted pinned-first then most-done.
 */
@Singleton
class AdHocLibraryRepository @Inject constructor(
    private val dao: AdHocWorkoutDao,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter(AdHocWorkoutMirrorDto::class.java)

    fun observeLibrary(): Flow<List<AdHocLibraryItem>> =
        dao.observeActive().map { rows ->
            rows
                .mapNotNull { row ->
                    runCatching { adapter.fromJson(row.payloadJson) }.getOrNull()?.toDomain()
                }
                .sortedWith(
                    compareByDescending<AdHocLibraryItem> { it.pinned }
                        .thenByDescending { it.runCount }
                        .thenBy { it.title.lowercase() },
                )
        }
}
