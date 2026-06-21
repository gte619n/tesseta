package com.gte619n.healthfitness.feature.medical

import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.DrugRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.data.reminders.ReminderEngine
import com.gte619n.healthfitness.data.reminders.ReminderSettingsRepository
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import java.time.LocalDate

internal fun sampleDrug(name: String = "Testosterone Cypionate") = Drug(
    drugId = "d1",
    name = name,
    category = DrugCategory.PRESCRIPTION,
    form = DrugForm.INJECTABLE_VIAL,
    defaultUnit = "mg",
    imageUrl = null,
    imageFallback = null,
)

internal fun sampleMedication(
    id: String = "m1",
    status: MedicationStatus = MedicationStatus.ACTIVE,
) = Medication(
    medicationId = id,
    drugId = "d1",
    drug = sampleDrug(),
    customName = null,
    status = status,
    dose = 200.0,
    unit = "mg",
    frequency = FrequencyConfig(FrequencyType.WEEKLY, timesPerPeriod = 1),
    timeSlots = emptyList(),
    protocolId = null,
    notes = null,
    prescribedBy = null,
    startDate = LocalDate.of(2026, 1, 1),
    endDate = null,
    discontinueReason = null,
    discontinueNotes = null,
    correlatedMarkers = emptyList(),
    adherence = null,
)

internal fun sampleDose(
    id: String = "m1",
    window: TimeWindow = TimeWindow.MORNING,
    taken: Boolean = false,
) = TodaysDose(
    medicationId = id,
    drugName = "Testosterone",
    window = window,
    dose = 200.0,
    unit = "mg",
    taken = taken,
    takenAt = null,
)

// MedicationRepository / DrugRepository / AdherenceRepository are concrete @Inject
// classes; MockK mocks them (final Kotlin classes are fine). Call counts are
// asserted with coVerify in the tests; mutable behavior is set via re-stubbing.

internal fun fakeMedicationRepository(
    meds: List<Medication> = emptyList(),
    doses: List<TodaysDose> = emptyList(),
    listError: Throwable? = null,
): MedicationRepository {
    val repo = mockk<MedicationRepository>()
    // offline-fix: the list screen now reads the reactive mirror stream; partition
    // happens in the ViewModel, so the fake just emits the seed list (or throws).
    every { repo.observe() } answers {
        if (listError != null) flow { throw listError } else MutableStateFlow(meds)
    }
    coEvery { repo.refresh() } returns Unit
    coEvery { repo.list(any()) } answers {
        listError?.let { throw it }
        val status = firstArg<MedicationStatus?>()
        if (status == null) meds else meds.filter { it.status == status }
    }
    coEvery { repo.get(any()) } answers {
        MedicationDetail(meds.first { it.medicationId == firstArg<String>() }, emptyList())
    }
    coEvery { repo.cachedDetail(any()) } answers {
        meds.firstOrNull { it.medicationId == firstArg<String>() }
            ?.let { MedicationDetail(it, emptyList()) }
    }
    coEvery { repo.create(any()) } returns sampleMedication()
    coEvery { repo.update(any(), any()) } returns sampleMedication()
    coEvery { repo.changeDose(any(), any()) } returns sampleMedication()
    coEvery { repo.discontinue(any(), any(), any(), any()) } returns
        sampleMedication(status = MedicationStatus.DISCONTINUED)
    coEvery { repo.reactivate(any(), any()) } returns sampleMedication()
    coEvery { repo.delete(any()) } returns Unit
    coEvery { repo.todaysDoses() } returns doses
    return repo
}

internal fun fakeReminderSettingsRepository(
    settings: ReminderSettings = ReminderSettings(),
): ReminderSettingsRepository {
    val repo = mockk<ReminderSettingsRepository>()
    coEvery { repo.get() } returns settings
    coEvery { repo.getCached() } returns settings
    coEvery { repo.set(any()) } answers { firstArg() }
    return repo
}

/** Relaxed mock — the engine's replan is fire-and-forget in these tests. */
internal fun fakeReminderEngine(): ReminderEngine = mockk(relaxed = true)

internal fun fakeAdherenceRepository(failOnLog: Boolean = false): AdherenceRepository {
    val repo = mockk<AdherenceRepository>()
    coEvery { repo.logDose(any(), any(), any(), any()) } answers {
        if (failOnLog) throw RuntimeException("network")
    }
    coEvery { repo.undoDose(any(), any(), any()) } answers {
        if (failOnLog) throw RuntimeException("network")
    }
    return repo
}

internal fun fakeDrugRepository(
    events: List<DrugLookupEvent> = emptyList(),
    catalog: List<Drug> = emptyList(),
): DrugRepository {
    val repo = mockk<DrugRepository>()
    coEvery { repo.catalog() } returns catalog
    coEvery { repo.get(any()) } returns sampleDrug()
    every { repo.lookupStream(any()) } returns events.asFlow()
    return repo
}
