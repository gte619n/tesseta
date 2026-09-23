package com.gte619n.healthfitness.shared.presentation.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodReadingRepository
import com.gte619n.healthfitness.shared.data.BloodTestReportRepository
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.LatestMarker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — shared Blood/Labs overview VM. Port of
 * android/feature-blood/.../BloodOverviewViewModel (Hilt → plain constructor,
 * java.time → kotlinx-datetime). Follows the reference [MedicationsViewModel]:
 * a sealed UiState + one reactive [StateFlow] off the Room mirror; the offline-
 * first "never blank back to Loading on re-entry" behaviour is shared, not
 * reimplemented per platform. SKIE exposes [state] to Swift as an AsyncSequence.
 */
class BloodOverviewViewModel(
    private val readings: BloodReadingRepository,
    private val reports: BloodTestReportRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val recentReports: List<BloodTestReport>,
            val trackedMarkers: List<LatestMarker>,
        ) : UiState

        data class Error(val message: String) : UiState
    }

    val state: StateFlow<UiState> = combine(
        readings.observeReadings(),
        reports.observeReports(),
    ) { r, rep ->
        UiState.Ready(
            recentReports = rep.sortedByDescending { it.sampleDate ?: LocalDate.fromEpochDays(0) }.take(10),
            trackedMarkers = LatestMarkers.derive(r, rep),
        ) as UiState
    }
        .catch { emit(UiState.Error(it.message ?: "Failed to load blood data")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    // D11 pull-to-refresh indicator: true while a foreground refresh (a delta pull
    // into the Room mirror) is in flight.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Monotonic-ish timestamp of the last successful refresh, so a re-entry within
    // REFRESH_TTL_MS reuses the mirror instead of re-pulling every time.
    private var lastRefreshAt: Long = 0L

    init {
        // Screen renders from the Room mirror immediately (state above); this only
        // revalidates, and only if the mirror is stale.
        refreshIfStale()
    }

    fun retry() = refresh()

    /** Pull-to-refresh entry point (D11): re-fill the mirror from the backend. */
    fun refresh() = doRefresh()

    /** On-entry revalidation: skipped when the mirror was refreshed recently. */
    private fun refreshIfStale() {
        val now = nowMs()
        if (lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_TTL_MS) return
        doRefresh()
    }

    private fun doRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching {
                readings.refresh()
                reports.refresh()
            }.onSuccess { lastRefreshAt = nowMs() }
            _isRefreshing.value = false
        }
    }

    // Wall-clock-independent millis for the TTL guard, exercisable in plain tests.
    private fun nowMs(): Long = getMonotonicMillis()

    private companion object {
        const val REFRESH_TTL_MS = 30_000L
    }
}

/**
 * Multiplatform monotonic-ish millis. On Android this maps to `System.nanoTime()`;
 * the TTL guard only needs a source that moves forward between calls, so a simple
 * common expect-less shim (elapsed since first read) is sufficient and keeps the
 * VM in commonMain. Uses [kotlinx.datetime.Clock] deltas, which are monotone
 * enough for a 30s revalidation window.
 */
private val monotonicOrigin: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()
private fun getMonotonicMillis(): Long =
    (kotlinx.datetime.Clock.System.now() - monotonicOrigin).inWholeMilliseconds
