package com.gte619n.healthfitness.data.medications

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.todaysDosesStore by preferencesDataStore("hf-todays-doses")

/**
 * offline-fix: single-slot persistent cache for the full "Today's doses" screen.
 *
 * Today's doses are a server-derived projection (medication schedule × adherence)
 * with no stable syncable entity to mirror in Room, so — like the dashboard card's
 * [com.gte619n.healthfitness.data.dashboard.DashboardDosesCache] — they get their
 * own small DataStore instead of a mirror row. It holds only the latest day's raw
 * server list (pre-adherence-overlay), tagged with the device-local date, so the
 * full screen renders the last-known checklist INSTANTLY on a cold/offline open
 * while a fresh pull runs (stale-while-revalidate, matching the dashboard). A cache
 * from a previous day is ignored (returns null) so the screen never shows
 * yesterday's checklist.
 *
 * This is a SEPARATE cache from the dashboard card's: the two screens read
 * different projections (the full screen's [TodaysDose] carries no image URL and
 * uses [com.gte619n.healthfitness.domain.medications.TimeWindow]), so each caches
 * its own domain shape rather than sharing one slot.
 *
 * Cleared on sign-out (this DataStore is NOT the encrypted Room DB, so the DbWipe
 * misses it) to avoid leaking one account's doses to the next on a shared device.
 */
@Singleton
class TodaysDosesCache @Inject constructor(
    @ApplicationContext private val context: Context,
    moshi: Moshi,
) {
    private val keyDate = stringPreferencesKey("date")
    private val keyList = stringPreferencesKey("doses")
    private val adapter = moshi.adapter<List<TodaysDose>>(
        Types.newParameterizedType(List::class.java, TodaysDose::class.java),
    )

    /** Cached doses for [date], or null if the cache is empty or for another day. */
    suspend fun read(date: String): List<TodaysDose>? {
        val prefs = context.todaysDosesStore.data.first()
        if (prefs[keyDate] != date) return null
        val json = prefs[keyList] ?: return null
        return runCatching { adapter.fromJson(json) }.getOrNull()
    }

    suspend fun write(date: String, doses: List<TodaysDose>) {
        context.todaysDosesStore.edit {
            it[keyDate] = date
            it[keyList] = adapter.toJson(doses)
        }
    }

    suspend fun clear() {
        context.todaysDosesStore.edit { it.clear() }
    }
}
