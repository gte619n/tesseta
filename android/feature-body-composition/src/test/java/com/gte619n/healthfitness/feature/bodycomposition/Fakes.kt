package com.gte619n.healthfitness.feature.bodycomposition

import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionMetric
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionPoint
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
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

class FakeBodyCompositionRepository(
    var snapshotToEmit: BodyCompositionSnapshot = sampleSnapshot(),
    var failRefresh: Boolean = false,
) : BodyCompositionRepository {
    private val flow = MutableSharedFlow<BodyCompositionSnapshot>(replay = 1)
    var refreshCount = 0
        private set

    override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = flow.asSharedFlow()

    override suspend fun refresh() {
        refreshCount++
        if (failRefresh) throw RuntimeException("boom")
        flow.emit(snapshotToEmit)
    }

    override suspend fun pointsInRange(
        metric: BodyCompositionMetric,
        from: Instant,
        to: Instant,
    ): List<BodyCompositionPoint> = emptyList()
}

class FakeDexaScanRepository(
    var summaries: List<DexaScanSummary> = emptyList(),
    var scan: DexaScan = sampleScan(),
    var failGet: Boolean = false,
    var failPatch: Boolean = false,
    var failDelete: Boolean = false,
    var uploadEvents: List<DexaUploadEvent> = emptyList(),
) : DexaScanRepository {
    private val flow = MutableSharedFlow<List<DexaScanSummary>>(replay = 1)
    var deleteCount = 0
        private set
    var lastPatch: Triple<String, String, Double?>? = null
        private set

    /** When set, patchField awaits this gate before returning (deterministic tests). */
    var patchGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    override fun observeScans(): Flow<List<DexaScanSummary>> = flow.asSharedFlow()

    override suspend fun refreshScans() {
        flow.emit(summaries)
    }

    override suspend fun getScan(scanId: String): DexaScan {
        if (failGet) throw RuntimeException("get failed")
        return scan
    }

    override suspend fun deleteScan(scanId: String) {
        deleteCount++
        if (failDelete) throw RuntimeException("delete failed")
    }

    override suspend fun downloadPdf(scanId: String): ByteArray = ByteArray(0)

    override suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan {
        lastPatch = Triple(scanId, path, value)
        patchGate?.await()
        if (failPatch) throw RuntimeException("patch failed")
        return scan
    }

    override fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent> =
        uploadEvents.asFlow()
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
