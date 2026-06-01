package com.gte619n.healthfitness.feature.blood.dashboard

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.LatestMarkers
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Feeds the dashboard's BloodPanel with the top-4 tracked markers, derived from
 * the same repositories as the Blood overview so the two surfaces stay in sync.
 */
@HiltViewModel
class DashboardBloodViewModel @Inject constructor(
    private val readings: BloodReadingRepository,
    private val reports: BloodTestReportRepository,
) : ViewModel() {

    val markers: StateFlow<List<LatestMarker>> =
        combine(readings.observeReadings(), reports.observeReports()) { r, rep ->
            LatestMarkers.derive(r, rep).take(4)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Monotonic timestamp of the last successful refresh, so resume-driven calls
    // within REFRESH_TTL_MS are skipped (this VM is refreshed on every dashboard
    // resume alongside DashboardViewModel).
    private var lastRefreshAt: Long = 0L

    init { refresh() }

    /**
     * Re-pull readings and reports from the backend; safe to call on resume.
     * Non-forced calls within [REFRESH_TTL_MS] of the last successful refresh are
     * skipped; the first load and any [force] = true caller always go through.
     */
    fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_TTL_MS) return
        viewModelScope.launch {
            runCatching {
                readings.refresh()
                reports.refresh()
            }.onSuccess { lastRefreshAt = SystemClock.elapsedRealtime() }
        }
    }

    private companion object {
        const val REFRESH_TTL_MS = 30_000L
    }
}
