package com.gte619n.healthfitness.feature.blood.dashboard

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

    init {
        viewModelScope.launch {
            runCatching {
                readings.refresh()
                reports.refresh()
            }
        }
    }
}
