package com.gte619n.healthfitness.feature.blood

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodReadingRepository
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.LatestMarkers
import com.gte619n.healthfitness.domain.blood.MarkerHistoryPoint
import com.gte619n.healthfitness.feature.blood.nav.BloodRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MarkerDetailViewModel @Inject constructor(
    private val readings: BloodReadingRepository,
    private val reports: BloodTestReportRepository,
    savedState: SavedStateHandle,
) : ViewModel() {

    val marker: BloodMarker = BloodMarker.valueOf(
        requireNotNull(savedState.get<String>(BloodRoutes.ARG_MARKER_KEY)) {
            "Missing ${BloodRoutes.ARG_MARKER_KEY} route arg"
        },
    )

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
        .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load marker")) }
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
