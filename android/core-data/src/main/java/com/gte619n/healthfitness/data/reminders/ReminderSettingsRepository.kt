package com.gte619n.healthfitness.data.reminders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reminderStore by preferencesDataStore("hf-medication-reminders")

/**
 * Medication-reminder settings: server-stored (the backend doc is the source
 * of truth) with a DataStore JSON cache so the on-device alarm planner works
 * offline and at boot, before any network round-trip.
 */
@Singleton
class ReminderSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ReminderSettingsApi,
    moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val keyJson = stringPreferencesKey("settings_json")
    private val dtoAdapter = moshi.adapter(ReminderSettingsDto::class.java)

    /**
     * Network-first read, falling back to the cached copy (then defaults) when
     * offline — the planner must never fail for lack of connectivity.
     */
    suspend fun get(): ReminderSettings = withContext(io) {
        runCatching { api.get() }
            .onSuccess { cache(it) }
            .map { it.toDomain() }
            .getOrElse { cached() ?: ReminderSettings() }
    }

    /** Cached-only read (no network) — used by alarm-time receivers. */
    suspend fun getCached(): ReminderSettings = withContext(io) {
        cached() ?: runCatching { api.get().also { cache(it) }.toDomain() }
            .getOrElse { ReminderSettings() }
    }

    suspend fun set(settings: ReminderSettings): ReminderSettings = withContext(io) {
        val stored = api.put(settings.toDto())
        cache(stored)
        stored.toDomain()
    }

    private suspend fun cache(dto: ReminderSettingsDto) {
        context.reminderStore.edit { it[keyJson] = dtoAdapter.toJson(dto) }
    }

    private suspend fun cached(): ReminderSettings? =
        context.reminderStore.data.first()[keyJson]
            ?.let { runCatching { dtoAdapter.fromJson(it) }.getOrNull() }
            ?.toDomain()

    // ---- mapping --------------------------------------------------------

    private fun ReminderSettingsDto.toDomain(): ReminderSettings = ReminderSettings(
        enabled = enabled ?: true,
        windowTimes = ReminderSettings.DEFAULT_WINDOW_TIMES + windows(windowTimes),
        perMedication = perMedication.orEmpty().mapValues { (_, o) ->
            MedicationReminderOverride(
                enabled = o.enabled ?: true,
                times = windows(o.times),
            )
        },
    )

    private fun windows(raw: Map<String, String>?): Map<TimeWindow, String> =
        raw.orEmpty().mapNotNull { (k, v) ->
            runCatching { TimeWindow.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()

    private fun ReminderSettings.toDto(): ReminderSettingsDto = ReminderSettingsDto(
        enabled = enabled,
        windowTimes = windowTimes.mapKeys { it.key.name },
        perMedication = perMedication.mapValues { (_, o) ->
            ReminderSettingsDto.MedicationOverrideDto(
                enabled = o.enabled,
                times = o.times.mapKeys { it.key.name },
            )
        },
    )
}
