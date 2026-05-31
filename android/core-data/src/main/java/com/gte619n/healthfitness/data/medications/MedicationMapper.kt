package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.medications.AdherenceSummary
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.ChangeType
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.DosagePeriod
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationDetail
import com.gte619n.healthfitness.domain.medications.MedicationHistoryEntry
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import com.gte619n.healthfitness.domain.medications.UpdateMedicationRequest

/**
 * DTO ↔ domain mapping for medications. String→enum conversion is safe:
 * unknown wire values fall back to a sensible default rather than throwing,
 * so a backend that adds an enum value never crashes the client.
 */
internal object MedicationMapper {

    // ---- enum-safe decode helpers ----

    private inline fun <reified T : Enum<T>> decode(value: String?, fallback: T): T {
        if (value == null) return fallback
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback
    }

    private inline fun <reified T : Enum<T>> decodeOrNull(value: String?): T? {
        if (value == null) return null
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    // ---- DTO -> domain ----

    fun toDomain(dto: DrugDto): Drug = Drug(
        drugId = dto.drugId,
        name = dto.name,
        aliases = dto.aliases.orEmpty(),
        category = decode(dto.category, DrugCategory.OTC),
        form = decode(dto.form, DrugForm.TABLET),
        defaultUnit = dto.defaultUnit,
        commonDoses = dto.commonDoses.orEmpty(),
        imageUrl = dto.imageUrl,
        imageFallback = dto.imageFallback,
        suggestedMarkers = dto.suggestedMarkers.orEmpty(),
        description = dto.description,
    )

    fun toDomain(dto: FrequencyConfigDto): FrequencyConfig = FrequencyConfig(
        type = decode(dto.type, FrequencyType.DAILY),
        timesPerPeriod = dto.timesPerPeriod,
        specificDays = dto.specificDays,
        cycle = dto.cycle?.let {
            FrequencyConfig.CycleConfig(
                onWeeks = it.onWeeks,
                offWeeks = it.offWeeks,
                startDate = it.startDate,
            )
        },
    )

    fun toDomain(dto: TimeSlotDto): TimeSlot = TimeSlot(
        window = decode(dto.window, TimeWindow.MORNING),
        dose = dto.dose,
    )

    fun toDomain(dto: DosagePeriodDto): DosagePeriod = DosagePeriod(
        dose = dto.dose,
        unit = dto.unit,
        startDate = dto.startDate,
        endDate = dto.endDate,
    )

    fun toDomain(dto: AdherenceSummaryDto): AdherenceSummary = AdherenceSummary(
        last30Days = dto.last30Days.orEmpty().map {
            AdherenceSummary.DayAdherence(date = it.date, taken = it.taken)
        },
        percentage = dto.percentage,
    )

    fun toDomain(dto: MedicationDto): Medication = Medication(
        medicationId = dto.medicationId,
        drugId = dto.drugId,
        drug = dto.drug?.let { toDomain(it) },
        customName = dto.customName,
        status = decode(dto.status, MedicationStatus.ACTIVE),
        dose = dto.dose,
        unit = dto.unit,
        frequency = toDomain(dto.frequency),
        timeSlots = dto.timeSlots.orEmpty().map { toDomain(it) },
        protocolId = dto.protocolId,
        notes = dto.notes,
        prescribedBy = dto.prescribedBy,
        startDate = dto.startDate,
        endDate = dto.endDate,
        discontinueReason = decodeOrNull<DiscontinueReason>(dto.discontinueReason),
        discontinueNotes = dto.discontinueNotes,
        correlatedMarkers = dto.correlatedMarkers.orEmpty(),
        dosagePeriods = dto.dosagePeriods.orEmpty().map { toDomain(it) },
        adherence = dto.adherence?.let { toDomain(it) },
    )

    fun toDomain(dto: MedicationHistoryEntryDto): MedicationHistoryEntry = MedicationHistoryEntry(
        historyId = dto.historyId,
        changeType = decode(dto.changeType, ChangeType.DOSE_CHANGE),
        previousValue = dto.previousValue.orEmpty(),
        newValue = dto.newValue.orEmpty(),
        changedAt = dto.changedAt,
        notes = dto.notes,
    )

    fun toDomain(dto: MedicationDetailDto): MedicationDetail = MedicationDetail(
        // The detail payload is flat (medication fields at the top level), so
        // build the Medication from those fields directly. The detail endpoint
        // doesn't carry the 30-day adherence summary — the detail screen loads
        // adherence separately — so `adherence` is null here.
        medication = Medication(
            medicationId = dto.medicationId,
            drugId = dto.drugId,
            drug = dto.drug?.let { toDomain(it) },
            customName = dto.customName,
            status = decode(dto.status, MedicationStatus.ACTIVE),
            dose = dto.dose,
            unit = dto.unit,
            frequency = toDomain(dto.frequency),
            timeSlots = dto.timeSlots.orEmpty().map { toDomain(it) },
            protocolId = dto.protocolId,
            notes = dto.notes,
            prescribedBy = dto.prescribedBy,
            startDate = dto.startDate,
            endDate = dto.endDate,
            discontinueReason = decodeOrNull<DiscontinueReason>(dto.discontinueReason),
            discontinueNotes = dto.discontinueNotes,
            correlatedMarkers = dto.correlatedMarkers.orEmpty(),
            dosagePeriods = dto.dosagePeriods.orEmpty().map { toDomain(it) },
            adherence = null,
        ),
        history = dto.history.orEmpty().map { toDomain(it) },
    )

    fun toDomain(dto: TodaysDoseDto): TodaysDose = TodaysDose(
        medicationId = dto.medicationId,
        drugName = dto.drugName,
        window = decode(dto.window, TimeWindow.MORNING),
        dose = dto.dose,
        unit = dto.unit,
        taken = dto.taken,
        takenAt = dto.takenAt,
    )

    // ---- domain -> DTO (write paths) ----

    fun toDto(period: DosagePeriod): DosagePeriodDto = DosagePeriodDto(
        dose = period.dose,
        unit = period.unit,
        startDate = period.startDate,
        endDate = period.endDate,
    )

    fun toDto(frequency: FrequencyConfig): FrequencyConfigDto = FrequencyConfigDto(
        type = frequency.type.name,
        timesPerPeriod = frequency.timesPerPeriod,
        specificDays = frequency.specificDays,
        cycle = frequency.cycle?.let {
            CycleConfigDto(onWeeks = it.onWeeks, offWeeks = it.offWeeks, startDate = it.startDate)
        },
    )

    fun toDto(slot: TimeSlot): TimeSlotDto = TimeSlotDto(
        window = slot.window.name,
        dose = slot.dose,
    )

    fun toDto(request: CreateMedicationRequest): CreateMedicationDto = CreateMedicationDto(
        drugId = request.drugId,
        customName = request.customName,
        customCategory = request.customCategory?.name,
        customForm = request.customForm?.name,
        dose = request.dose,
        unit = request.unit,
        frequency = toDto(request.frequency),
        timeSlots = request.timeSlots.map { toDto(it) },
        notes = request.notes,
        prescribedBy = request.prescribedBy,
        correlatedMarkers = request.correlatedMarkers,
    )

    fun toDto(request: UpdateMedicationRequest): UpdateMedicationDto = UpdateMedicationDto(
        customName = request.customName,
        dose = request.dose,
        unit = request.unit,
        frequency = request.frequency?.let { toDto(it) },
        timeSlots = request.timeSlots?.map { toDto(it) },
        notes = request.notes,
        prescribedBy = request.prescribedBy,
        correlatedMarkers = request.correlatedMarkers,
        startDate = request.startDate,
        dosagePeriods = request.dosagePeriods?.map { toDto(it) },
        changeNotes = request.changeNotes,
    )

    fun toDto(request: ChangeDoseRequest): ChangeDoseDto = ChangeDoseDto(
        dose = request.dose,
        unit = request.unit,
        startDate = request.startDate,
        changeNotes = request.changeNotes,
    )
}
