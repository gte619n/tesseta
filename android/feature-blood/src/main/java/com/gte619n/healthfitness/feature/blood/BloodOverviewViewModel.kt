package com.gte619n.healthfitness.feature.blood

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.data.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.LatestMarkers
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BloodOverviewViewModel @Inject constructor(
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
            recentReports = rep.sortedByDescending { it.sampleDate ?: LocalDate.MIN }.take(10),
            trackedMarkers = LatestMarkers.derive(r, rep),
        ) as UiState
    }
        .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load blood data")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    // IMPL-AND-20 (Phase 6, D11): pull-to-refresh indicator state. True while a
    // foreground refresh (a delta pull into the Room mirror) is in flight.
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Monotonic timestamp of the last successful background refresh, so a re-entry
    // within REFRESH_TTL_MS reuses the mirror instead of re-pulling every time.
    private var lastRefreshAt: Long = 0L

    init {
        // Screen renders from the Room mirror immediately (state stateIn above);
        // this only revalidates, and only if the mirror is stale.
        refreshIfStale()
    }

    fun retry() = refresh()

    /** Pull-to-refresh entry point (D11): re-fill the mirror from the backend. */
    fun refresh() = doRefresh()

    /** On-entry revalidation: skipped when the mirror was refreshed recently. */
    private fun refreshIfStale() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_TTL_MS) return
        doRefresh()
    }

    private fun doRefresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching {
                readings.refresh()
                reports.refresh()
            }.onSuccess { lastRefreshAt = SystemClock.elapsedRealtime() }
            _isRefreshing.value = false
        }
    }

    private companion object {
        const val REFRESH_TTL_MS = 30_000L
    }
}
