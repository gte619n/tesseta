package com.gte619n.healthfitness.shared.presentation.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodReadingRepository
import com.gte619n.healthfitness.shared.data.BloodTestReportRepository
import com.gte619n.healthfitness.shared.domain.blood.BloodMarker
import com.gte619n.healthfitness.shared.domain.blood.LatestMarker
import com.gte619n.healthfitness.shared.domain.blood.MarkerHistoryPoint
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — shared single-marker detail VM (the time-series
 * chart + readings table target). Port of android/feature-blood/.../
 * MarkerDetailViewModel; the SavedStateHandle route-arg is replaced by a plain
 * constructor [marker] param (the platform nav passes it in — SwiftUI resolves
 * it from the [BloodMarker] the row carried). Reactively derives the same
 * [LatestMarkers.derive] view as the overview so both surfaces agree.
 */
class MarkerDetailViewModel(
    private val readings: BloodReadingRepository,
    private val reports: BloodTestReportRepository,
    val marker: BloodMarker,
) : ViewModel() {

    /** One row in the readings table for this marker. */
    data class HistoryRow(
        val date: LocalDate,
        val value: Double,
        val unit: String,
        val sourceLabel: String,
    )

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(
            val latest: LatestMarker,
            val history: List<MarkerHistoryPoint>,
            val rows: List<HistoryRow>,
        ) : UiState

        data class Error(val message: String) : UiState
    }

    val state: StateFlow<UiState> = combine(
        readings.observeReadings(),
        reports.observeReports(),
    ) { r, rep ->
        val latest = LatestMarkers.derive(r, rep)
            .first { it.marker == marker }
        val rows = latest.history
            .sortedByDescending { it.date }
            .map { point ->
                HistoryRow(
                    date = point.date,
                    value = point.value,
                    unit = latest.unit,
                    sourceLabel = when (val s = point.source) {
                        MarkerHistoryPoint.Source.Manual -> "Manual"
                        is MarkerHistoryPoint.Source.Lab ->
                            if (s.labSource.isBlank()) "Lab" else "Lab — ${s.labSource}"
                    },
                )
            }
        UiState.Ready(latest = latest, history = latest.history, rows = rows) as UiState
    }
        .catch { emit(UiState.Error(it.message ?: "Failed to load marker")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    init {
        viewModelScope.launch {
            runCatching {
                readings.refresh()
                reports.refresh()
            }
        }
    }
}
