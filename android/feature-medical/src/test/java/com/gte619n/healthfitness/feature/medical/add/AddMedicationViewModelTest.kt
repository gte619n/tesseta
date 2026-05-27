package com.gte619n.healthfitness.feature.medical.add

import app.cash.turbine.test
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import com.gte619n.healthfitness.feature.medical.list.FakeMedicationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddMedicationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `local catalog filter narrows by name`() = runTest(dispatcher) {
        val drugs = FakeDrugRepository(
            catalog = listOf(
                drugFixture("d1", "Atorvastatin"),
                drugFixture("d2", "Rosuvastatin"),
                drugFixture("d3", "Metformin"),
            ),
        )
        val meds = FakeMedicationRepository()
        val vm = AddMedicationViewModel(drugs, meds)
        advanceUntilIdle()      // load catalog

        vm.state.test {
            // initial emission
            skipItems(1)
            vm.onQueryChange("statin")
            // emits twice: query change + filtered update
            val s = expectMostRecentItem()
            assertEquals("statin", s.query)
            assertEquals(2, s.filtered.size)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test fun `sse Found advances to FORM step and pre-fills unit`() = runTest(dispatcher) {
        val drugs = FakeDrugRepository(
            catalog = emptyList(),
            lookup = flow {
                emit(DrugLookupEvent.Progress("searching", "Looking up..."))
                emit(DrugLookupEvent.Progress("generating_image", "Generating image..."))
                emit(DrugLookupEvent.Found(drugFixture("dx", "Testosterone Cypionate", unit = "mg")))
            },
        )
        val vm = AddMedicationViewModel(drugs, FakeMedicationRepository())
        advanceUntilIdle()

        vm.onQueryChange("testosterone cypionate")
        advanceTimeBy(500)      // past the 400ms debounce
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(AddMedicationStep.Form, state.step)
        assertEquals("Testosterone Cypionate", state.selectedDrug?.name)
        assertEquals("mg", state.unit)
    }

    @Test fun `sse NotFound surfaces in state for manual fallback`() = runTest(dispatcher) {
        val drugs = FakeDrugRepository(
            lookup = flow { emit(DrugLookupEvent.NotFound("No drug found matching: zzz")) },
        )
        val vm = AddMedicationViewModel(drugs, FakeMedicationRepository())
        advanceUntilIdle()
        vm.onQueryChange("zzz")
        advanceTimeBy(500)
        advanceUntilIdle()

        val event = vm.state.value.lookupEvent
        assertTrue(event is DrugLookupEvent.NotFound)
        assertEquals(AddMedicationStep.Search, vm.state.value.step)

        vm.chooseManualEntry()
        assertEquals(AddMedicationStep.Custom, vm.state.value.step)
    }

    @Test fun `submit success invokes onDone`() = runTest(dispatcher) {
        val drugs = FakeDrugRepository()
        val meds = FakeMedicationRepository()
        val vm = AddMedicationViewModel(drugs, meds)
        advanceUntilIdle()

        vm.onDoseChange(200.0)
        vm.selectDrug(drugFixture("d1", "Test"))
        var fired = false
        vm.submit { fired = true }
        advanceUntilIdle()
        assertTrue(fired)
        assertNotNull(meds.lastCreated)
        assertEquals(200.0, meds.lastCreated!!.dose, 0.001)
        assertEquals("d1", meds.lastCreated!!.drugId)
        assertNull(vm.state.value.error)
    }
}

internal class FakeDrugRepository(
    private val catalog: List<Drug> = emptyList(),
    private val lookup: Flow<DrugLookupEvent> = MutableSharedFlow(),
) : DrugRepository {
    override suspend fun catalog(query: String?): List<Drug> = catalog
    override suspend fun get(drugId: String): Drug = catalog.first { it.drugId == drugId }
    override fun lookupStream(query: String): Flow<DrugLookupEvent> = lookup
}

internal fun drugFixture(id: String, name: String, unit: String = "mg"): Drug = Drug(
    drugId = id,
    name = name,
    aliases = emptyList(),
    category = DrugCategory.PRESCRIPTION,
    form = DrugForm.TABLET,
    defaultUnit = unit,
    commonDoses = emptyList(),
    imageUrl = null,
    imageFallback = null,
    suggestedMarkers = emptyList(),
    description = null,
)
