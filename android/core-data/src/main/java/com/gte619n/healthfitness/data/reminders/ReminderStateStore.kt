package com.gte619n.healthfitness.data.reminders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.data.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reminderStateStore by preferencesDataStore("hf-reminder-state")

/**
 * Durable per-day state of the single rolling medication reminder, so the
 * engine's alert-vs-silent diff and the user's swipe-dismissal survive process
 * death (both previously lived in memory, which made every background restart
 * re-alert and every replan resurrect a swiped-away notification):
 *
 *  - [postedKeys] — the `(med:window)` keys currently shown on the notification.
 *    A refresh re-alerts only for keys never posted (nor dismissed) today.
 *  - [dismissedKeys] — keys the user swiped away today. While every outstanding
 *    key is dismissed the engine stops re-posting; a NEW key crossing into due
 *    re-posts (and re-alerts) the full list.
 *
 * Keys are stored with the date they belong to; reads for any other date see an
 * empty set, and [clear] wipes everything at the midnight rollover.
 */
interface ReminderStateStore {
    suspend fun postedKeys(date: LocalDate): Set<String>
    suspend fun setPostedKeys(date: LocalDate, keys: Set<String>)
    suspend fun dismissedKeys(date: LocalDate): Set<String>
    /** Union [keys] into the dismissed set for [date] (replacing another day's). */
    suspend fun addDismissedKeys(date: LocalDate, keys: Set<String>)
    suspend fun clear()
}

@Singleton
class DataStoreReminderStateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ReminderStateStore {

    private val postedDate = stringPreferencesKey("posted_date")
    private val posted = stringSetPreferencesKey("posted_keys")
    private val dismissedDate = stringPreferencesKey("dismissed_date")
    private val dismissed = stringSetPreferencesKey("dismissed_keys")

    override suspend fun postedKeys(date: LocalDate): Set<String> = withContext(io) {
        val prefs = context.reminderStateStore.data.first()
        if (prefs[postedDate] == date.toString()) prefs[posted].orEmpty() else emptySet()
    }

    override suspend fun setPostedKeys(date: LocalDate, keys: Set<String>) {
        withContext(io) {
            context.reminderStateStore.edit {
                it[postedDate] = date.toString()
                it[posted] = keys
            }
        }
    }

    override suspend fun dismissedKeys(date: LocalDate): Set<String> = withContext(io) {
        val prefs = context.reminderStateStore.data.first()
        if (prefs[dismissedDate] == date.toString()) prefs[dismissed].orEmpty() else emptySet()
    }

    override suspend fun addDismissedKeys(date: LocalDate, keys: Set<String>) {
        withContext(io) {
            context.reminderStateStore.edit {
                val sameDay = it[dismissedDate] == date.toString()
                it[dismissedDate] = date.toString()
                it[dismissed] = if (sameDay) it[dismissed].orEmpty() + keys else keys
            }
        }
    }

    override suspend fun clear() {
        withContext(io) { context.reminderStateStore.edit { it.clear() } }
    }
}
