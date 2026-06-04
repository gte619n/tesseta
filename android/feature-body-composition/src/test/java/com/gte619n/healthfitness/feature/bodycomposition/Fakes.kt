package com.gte619n.healthfitness.feature.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.data.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

fun emptySnapshot(): BodyCompositionSnapshot = BodyCompositionSnapshot(
    latestWeightKg = null,
    latestBodyFatPercent = null,
    latestLeanMassKg = null,
    latestBmi = null,
    latestSampleTime = null,
    sevenDayDeltaKg = null,
    ninetyDayDeltaKg = null,
    series90d = emptyList(),
)

fun sampleSnapshot(): BodyCompositionSnapshot = BodyCompositionSnapshot(
    latestWeightKg = 80.0,
    latestBodyFatPercent = 18.0,
    latestLeanMassKg = 60.0,
    latestBmi = 24.0,
    latestSampleTime = Instant.parse("2026-05-01T00:00:00Z"),
    sevenDayDeltaKg = -0.5,
    ninetyDayDeltaKg = -2.0,
    series90d = listOf(
        BodyCompositionPoint("1", BodyCompositionMetric.WEIGHT_KG, 82.0, Instant.parse("2026-02-01T00:00:00Z"), null, null),
        BodyCompositionPoint("2", BodyCompositionMetric.WEIGHT_KG, 80.0, Instant.parse("2026-05-01T00:00:00Z"), null, null),
    ),
)

fun sampleScan(scanId: String = "scan-1"): DexaScan = DexaScan(
    scanId = scanId,
    measuredOn = java.time.LocalDate.parse("2026-05-01"),
    sourceFacility = "Test Clinic",
    totalMassLb = 176.0,
    leanTissueLb = 130.0,
    fatTissueLb = 40.0,
    totalBodyFatPercent = 22.0,
    visceralFatLb = 1.2,
    androidGynoidRatio = 0.9,
    trunk = null,
    android = null,
    gynoid = null,
    armsTotal = null,
    armsRight = null,
    armsLeft = null,
    legsTotal = null,
    legsRight = null,
    legsLeft = null,
    bmdTScore = -0.5,
    bmdZScore = 0.2,
    restingMetabolicRateKcal = 1600,
)

// BodyCompositionRepository / DexaScanRepository are concrete @Inject classes;
// MockK mocks them (final Kotlin classes are fine). Backing flows preserve the
// observe/refresh behavior; call counts are asserted with coVerify in the tests.

fun fakeBodyCompositionRepository(
    snapshotToEmit: BodyCompositionSnapshot = sampleSnapshot(),
    failRefresh: Boolean = false,
): BodyCompositionRepository {
    val flow = MutableSharedFlow<BodyCompositionSnapshot>(replay = 1)
    val repo = mockk<BodyCompositionRepository>()
    every { repo.observeSnapshot() } returns flow.asSharedFlow()
    coEvery { repo.refresh() } coAnswers {
        if (failRefresh) throw RuntimeException("boom")
        flow.emit(snapshotToEmit)
    }
    coEvery { repo.pointsInRange(any(), any(), any()) } returns emptyList()
    return repo
}

fun fakeDexaScanRepository(
    summaries: List<DexaScanSummary> = emptyList(),
    scan: DexaScan = sampleScan(),
    failGet: Boolean = false,
    failPatch: Boolean = false,
    failDelete: Boolean = false,
    uploadEvents: List<DexaUploadEvent> = emptyList(),
    /** When set, patchField awaits this gate before returning (deterministic tests). */
    patchGate: CompletableDeferred<Unit>? = null,
): DexaScanRepository {
    val flow = MutableSharedFlow<List<DexaScanSummary>>(replay = 1)
    val repo = mockk<DexaScanRepository>()
    every { repo.observeScans() } returns flow.asSharedFlow()
    coEvery { repo.refreshScans() } coAnswers { flow.emit(summaries) }
    coEvery { repo.getScan(any()) } answers {
        if (failGet) throw RuntimeException("get failed")
        scan
    }
    coEvery { repo.deleteScan(any()) } answers {
        if (failDelete) throw RuntimeException("delete failed")
    }
    coEvery { repo.downloadPdf(any()) } returns ByteArray(0)
    coEvery { repo.patchField(any(), any(), any()) } coAnswers {
        patchGate?.await()
        if (failPatch) throw RuntimeException("patch failed")
        scan
    }
    every { repo.uploadPdf(any(), any()) } returns uploadEvents.asFlow()
    return repo
}

class FakeUnitPreferencesRepository(
    weight: WeightUnit = WeightUnit.POUNDS,
) : UnitPreferencesRepository {
    private val _preferences = MutableStateFlow(UnitPreferences(weight = weight))
    override val preferences: Flow<UnitPreferences> = _preferences

    override suspend fun setHeightUnit(unit: HeightUnit) {
        _preferences.value = _preferences.value.copy(height = unit)
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        _preferences.value = _preferences.value.copy(weight = unit)
    }

    override suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        _preferences.value = _preferences.value.copy(temperature = unit)
    }
}
