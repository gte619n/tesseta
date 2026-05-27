package com.gte619n.healthfitness.mobile.dashboard

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.HistoryPoint
import com.gte619n.healthfitness.domain.dashboard.MarkerTone
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.CardState
import com.gte619n.healthfitness.mobile.dashboard.viewmodel.DashboardUiState
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Round 2 Stage D coverage for the dashboard surfaces. Renders the
 * `*Content` variants of both [PhoneTodayContent] and
 * [FoldableDashboardContent] (extracted in this stage so the tests can
 * skip the Hilt graph) against hand-built `DashboardUiState` fixtures.
 * The doses card is rendered via [TodaysDosesSectionContent] with an
 * explicit `TodaysDosesUiState` passed through the `dosesContent` slot,
 * which also bypasses Hilt for the dose section's ViewModel.
 *
 * Phone snapshots:
 *   - [phone_loading] — every card in Loading; vitals row falls back to
 *     the fixture vital while the weight card is loading; doses say
 *     "Loading...".
 *   - [phone_loaded] — `bodyComposition` Loaded with a 90-day series so
 *     the weight vital renders the lb value + delta; doses populated.
 *   - [phone_error] — `bodyComposition` Errored to show the partial
 *     degradation path (weight vital falls back to fixture).
 *
 * Foldable snapshot:
 *   - [foldable_loaded] — all three cards Loaded so the hero, BloodPanel,
 *     and TodayCard render their populated layouts at the foldable
 *     width. Uses a wider device config that approximates the Medium
 *     width-class the foldable layout switches on in production.
 *
 * Today card's calories / macros / workout sections still pull from
 * `DashboardFallbacks` — they're fixture data, not state-driven, so the
 * snapshots intentionally show them in their static shape.
 */
class DashboardScreenPaparazziTest {

    private val phoneDevice = DeviceConfig.PIXEL_5.copy(nightMode = NightMode.NOTNIGHT)

    private val foldableDevice = DeviceConfig.PIXEL_5.copy(
        nightMode = NightMode.NOTNIGHT,
        screenWidth = 1840,
        screenHeight = 1080,
        orientation = ScreenOrientation.LANDSCAPE,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = phoneDevice)

    private val now: Instant = Instant.parse("2026-05-12T08:00:00Z")

    private fun sampleSnapshot(): BodyCompositionSnapshot {
        // 12 weekly points trending down 85.5 -> 83.4 kg.
        val series = (0 until 12).map { i ->
            BodyCompositionPoint(
                recordId = "wt-$i",
                metric = BodyCompositionMetric.WEIGHT_KG,
                value = 85.5 - i * 0.18,
                sampleTime = now.minusSeconds((11 - i) * 7L * 86_400L),
                sourcePlatform = "withings",
                recordingMethod = "scale",
            )
        }
        return BodyCompositionSnapshot(
            latestWeightKg = 83.4,
            latestBodyFatPercent = 17.2,
            latestLeanMassKg = 65.4,
            latestBmi = 23.6,
            latestSampleTime = now,
            sevenDayDeltaKg = -0.4,
            ninetyDayDeltaKg = -2.0,
            series90d = series,
        )
    }

    private fun sampleBloodMarkers(): List<BloodMarkerSummary> = listOf(
        BloodMarkerSummary(
            markerKey = "LDL",
            displayName = "LDL",
            value = 88.0,
            unit = "mg/dL",
            tone = MarkerTone.Good,
            goodFillPct = 0.375f,
            goodLeftPct = 0f,
            tickPct = 0.3f,
            displayMin = 40.0,
            goodThreshold = 100.0,
            displayMax = 200.0,
            history = listOf(
                HistoryPoint(LocalDate.of(2026, 1, 12), 102.0),
                HistoryPoint(LocalDate.of(2026, 4, 12), 88.0),
            ),
        ),
        BloodMarkerSummary(
            markerKey = "HBA1C",
            displayName = "HbA1c",
            value = 5.4,
            unit = "%",
            tone = MarkerTone.Good,
            goodFillPct = 0.5f,
            goodLeftPct = 0f,
            tickPct = 0.4f,
            displayMin = 4.0,
            goodThreshold = 5.6,
            displayMax = 7.0,
            history = listOf(HistoryPoint(LocalDate.of(2026, 4, 12), 5.4)),
        ),
        BloodMarkerSummary(
            markerKey = "APO_B",
            displayName = "ApoB",
            value = 96.0,
            unit = "mg/dL",
            tone = MarkerTone.Warn,
            goodFillPct = 0.5f,
            goodLeftPct = 0f,
            tickPct = 0.6f,
            displayMin = 40.0,
            goodThreshold = 90.0,
            displayMax = 140.0,
            history = listOf(HistoryPoint(LocalDate.of(2026, 4, 12), 96.0)),
        ),
    )

    private fun sampleTodaysDoses(): List<TodaysDose> = listOf(
        TodaysDose(
            medicationId = "med-1",
            drugName = "Atorvastatin",
            imageUrl = null,
            window = TimeWindow.EVENING,
            dose = 20.0,
            unit = "mg",
            taken = false,
            takenAt = null,
        ),
        TodaysDose(
            medicationId = "med-2",
            drugName = "Vitamin D3",
            imageUrl = null,
            window = TimeWindow.MORNING,
            dose = 5000.0,
            unit = "IU",
            taken = true,
            takenAt = now,
        ),
    )

    @Test
    fun phone_loading() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                PhoneTodayContent(
                    ui = DashboardUiState(
                        bodyComposition = CardState.Loading,
                        blood = CardState.Loading,
                        todaysDoses = CardState.Loading,
                    ),
                    dosesContent = {
                        TodaysDosesSectionContent(
                            state = TodaysDosesUiState.Loading,
                            onSeeAll = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun phone_loaded() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                PhoneTodayContent(
                    ui = DashboardUiState(
                        bodyComposition = CardState.Loaded(sampleSnapshot()),
                        blood = CardState.Loaded(sampleBloodMarkers()),
                        todaysDoses = CardState.Loading, // unused on phone
                    ),
                    dosesContent = {
                        TodaysDosesSectionContent(
                            state = TodaysDosesUiState.Ready(sampleTodaysDoses()),
                            onSeeAll = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun phone_error() {
        paparazzi.snapshot {
            HealthFitnessTheme {
                PhoneTodayContent(
                    ui = DashboardUiState(
                        bodyComposition = CardState.Error("Couldn't load weight"),
                        blood = CardState.Loaded(sampleBloodMarkers()),
                        todaysDoses = CardState.Loading,
                    ),
                    dosesContent = {
                        TodaysDosesSectionContent(
                            state = TodaysDosesUiState.Ready(sampleTodaysDoses()),
                            onSeeAll = {},
                        )
                    },
                )
            }
        }
    }

    @Test
    fun foldable_loaded() {
        paparazzi.unsafeUpdateConfig(deviceConfig = foldableDevice)
        paparazzi.snapshot {
            HealthFitnessTheme {
                FoldableDashboardContent(
                    ui = DashboardUiState(
                        bodyComposition = CardState.Loaded(sampleSnapshot()),
                        blood = CardState.Loaded(sampleBloodMarkers()),
                        todaysDoses = CardState.Loading,
                    ),
                    dosesContent = {
                        TodaysDosesSectionContent(
                            state = TodaysDosesUiState.Ready(sampleTodaysDoses()),
                            onSeeAll = {},
                        )
                    },
                )
            }
        }
    }
}
