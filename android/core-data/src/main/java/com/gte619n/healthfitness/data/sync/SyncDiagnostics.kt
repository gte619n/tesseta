package com.gte619n.healthfitness.data.sync

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-STAB (Workstream B) — the single place sync/network failures are recorded
 * instead of being swallowed.
 *
 * Before this, every failure in the sync path was caught and dropped: the
 * WorkManager workers folded any throwable into `Result.retry()` with no log, the
 * outbox drain updated only the row state, and repository `refresh()` callers
 * wrapped the call in a bare `runCatching { }`. The user saw a generic "Some
 * changes didn't sync" (or nothing at all) with no way to know *why*.
 *
 * [record] logs the failure (so it shows in logcat / crash breadcrumbs) and keeps
 * a bounded in-memory ring of the most recent errors. [lastError] drives the
 * detail line on the global sync banner; [recent] backs the debug "Sync log"
 * screen. The buffer is intentionally process-scoped (not persisted) — it is a
 * diagnostic aid, not durable state; the outbox row itself remains the source of
 * truth for what still needs to sync.
 */
@Singleton
class SyncDiagnostics @Inject constructor() {

    /** One recorded failure. [httpCode] is null for transport/non-HTTP errors. */
    data class Entry(
        val atMillis: Long,
        val source: String,
        val table: String?,
        val entityId: String?,
        val httpCode: Int?,
        val terminal: Boolean,
        val message: String,
    ) {
        /** Short, user-facing reason for the sync banner detail line. */
        fun shortReason(): String {
            val where = table?.let { " ($it)" } ?: ""
            val codePart = httpCode?.let { "HTTP $it" } ?: "Network error"
            return "$codePart$where: $message"
        }
    }

    private val _recent = MutableStateFlow<List<Entry>>(emptyList())
    val recent: StateFlow<List<Entry>> = _recent

    private val _lastError = MutableStateFlow<Entry?>(null)
    val lastError: StateFlow<Entry?> = _lastError

    fun record(
        source: String,
        message: String,
        table: String? = null,
        entityId: String? = null,
        httpCode: Int? = null,
        terminal: Boolean = false,
        cause: Throwable? = null,
    ) {
        val entry = Entry(
            atMillis = System.currentTimeMillis(),
            source = source,
            table = table,
            entityId = entityId,
            httpCode = httpCode,
            terminal = terminal,
            message = message,
        )
        // Guard so the (Android-only) logger never breaks logic on the plain-JVM
        // unit-test classpath, where android.util.Log is not mocked.
        runCatching { Log.w(TAG, "[$source] ${entry.shortReason()}", cause) }
        _lastError.value = entry
        _recent.update { prev -> (listOf(entry) + prev).take(MAX_ENTRIES) }
    }

    /** Clear the surfaced last error (e.g. after a clean drain). The [recent] ring is kept. */
    fun clearLastError() {
        _lastError.value = null
    }

    fun clearAll() {
        _lastError.value = null
        _recent.value = emptyList()
    }

    companion object {
        private const val TAG = "HFSync"
        private const val MAX_ENTRIES = 50
    }
}
