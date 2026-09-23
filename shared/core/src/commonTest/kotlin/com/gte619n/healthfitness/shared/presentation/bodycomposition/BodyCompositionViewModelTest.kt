package com.gte619n.healthfitness.shared.presentation.bodycomposition

import com.gte619n.healthfitness.shared.data.BodyCompositionRepository
import com.gte619n.healthfitness.shared.data.DexaScanRepository
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.shared.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — the shared body-composition overview VM.
 * Pure/common → runs identically on JVM and iOS. Verifies the reactive
 * snapshot+scans combine folds into a non-loading Ready state, the weight-unit
 * projection, the on-entry refresh, and that a refresh failure with data already
 * on screen keeps the data (offline-first) rather than blanking to an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BodyCompositionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val snapshot = BodyCompositionSnapshot(
        latestWeightKg = 82.0,
        latestBodyFatPercent = 18.0,
        latestLeanMassKg = 66.0,
        latestBmi = 24.0,
        latestSampleTime = null,
        sevenDayDeltaKg = -0.5,
        ninetyDayDeltaKg = -2.0,
        series90d = emptyList(),
    )

    private val scan = DexaScanSummary(
        scanId = "s-1",
        measuredOn = LocalDate.parse("2026-09-01"),
        sourceFacility = "BodySpec",
        totalMassLb = 180.0,
        totalBodyFatPercent = 18.0,
    )

    @Test
    fun combinesSnapshotAndScansIntoReadyState() = runTest {
        val body = FakeBodyRepo(MutableStateFlow(snapshot))
        val dexa = FakeDexaRepo(MutableStateFlow(listOf(scan)))
        val vm = BodyCompositionViewModel(body, dexa, FakeUnitPrefs(WeightUnit.KILOGRAMS))
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.weightUnit.collect {} }
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(82.0, state.snapshot?.latestWeightKg)
        assertEquals(1, state.dexaScans.size)
        assertNull(state.error)
        assertEquals(WeightUnit.KILOGRAMS, vm.weightUnit.value)
        assertTrue(body.refreshCount >= 1)
        assertTrue(dexa.refreshCount >= 1)
    }

    @Test
    fun refreshFailureKeepsMirrorDataOnScreen() = runTest {
        val body = FakeBodyRepo(MutableStateFlow(snapshot), refreshThrows = true)
        val dexa = FakeDexaRepo(MutableStateFlow(listOf(scan)))
        val vm = BodyCompositionViewModel(body, dexa, FakeUnitPrefs())
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.weightUnit.collect {} }
        advanceUntilIdle()

        // Data still present, no error surfaced because we had something to show.
        assertEquals(82.0, vm.state.value.snapshot?.latestWeightKg)
        assertNull(vm.state.value.error)
    }

    @Test
    fun refreshFailureWithNoDataSurfacesError() = runTest {
        val body = FakeBodyRepo(emptyFlow(), refreshThrows = true)
        val dexa = FakeDexaRepo(MutableStateFlow(emptyList()))
        val vm = BodyCompositionViewModel(body, dexa, FakeUnitPrefs())
        backgroundScope.launch { vm.state.collect {} }
        backgroundScope.launch { vm.weightUnit.collect {} }
        advanceUntilIdle()

        assertNull(vm.state.value.snapshot)
        assertTrue(vm.state.value.error != null)
        assertFalse(vm.state.value.loading)
    }
}

private class FakeBodyRepo(
    private val flow: Flow<BodyCompositionSnapshot> = emptyFlow(),
    private val refreshThrows: Boolean = false,
) : BodyCompositionRepository {
    var refreshCount = 0
    override fun observeSnapshot(): Flow<BodyCompositionSnapshot> = flow
    override suspend fun refresh() {
        refreshCount++
        if (refreshThrows) throw RuntimeException("offline")
    }
}

private class FakeDexaRepo(
    private val flow: Flow<List<DexaScanSummary>> = MutableStateFlow(emptyList()),
) : DexaScanRepository {
    var refreshCount = 0
    override fun observeScans(): Flow<List<DexaScanSummary>> = flow
    override suspend fun refreshScans() { refreshCount++ }
    override suspend fun getScan(scanId: String): DexaScan = throw NotImplementedError()
    override suspend fun deleteScan(scanId: String) {}
    override suspend fun patchField(scanId: String, path: String, value: Double?): DexaScan = throw NotImplementedError()
    override suspend fun downloadPdf(scanId: String): ByteArray = ByteArray(0)
    override fun uploadPdf(fileName: String, bytes: ByteArray): Flow<DexaUploadEvent> = emptyFlow()
}

private class FakeUnitPrefs(
    weight: WeightUnit = WeightUnit.POUNDS,
) : UnitPreferencesRepository {
    override val preferences: Flow<UnitPreferences> = MutableStateFlow(UnitPreferences(weight = weight))
    override suspend fun setHeightUnit(unit: com.gte619n.healthfitness.shared.domain.prefs.HeightUnit) {}
    override suspend fun setWeightUnit(unit: WeightUnit) {}
    override suspend fun setTemperatureUnit(unit: com.gte619n.healthfitness.shared.domain.prefs.TemperatureUnit) {}
}
