package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.medications.AdherenceSummary
import com.gte619n.healthfitness.domain.medications.ChangeDoseRequest
import com.gte619n.healthfitness.domain.medications.ChangeType
import com.gte619n.healthfitness.domain.medications.CreateMedicationRequest
import com.gte619n.healthfitness.domain.medications.DosagePeriod
import com.gte619n.healthfitness.domain.medications.DayOfWeek
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
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
 * DTO ↔ domain mapping. Enum-name-as-string is decoded with
 * `enumValueOf<T>()` and a per-enum fallback so a stale Android client
 * against a newer backend never crashes — unknown enum values fall to a
 * safe default (matches the dashboard's TodaysDosesMapper pattern).
 */
internal object MedicationMapper {

    fun toDomain(dto: MedicationDto): Medication = Medication(
        medicationId = dto.medicationId,
        drugId = dto.drugId,
        drug = dto.drug?.let(::toDomain),
        customName = dto.customName,
        status = parseStatus(dto.status),
        dose = dto.dose,
        unit = dto.unit,
        frequency = toDomain(dto.frequency),
        timeSlots = dto.timeSlots.map(::toDomain),
        protocolId = dto.protocolId,
        notes = dto.notes,
        prescribedBy = dto.prescribedBy,
        startDate = dto.startDate,
        endDate = dto.endDate,
        discontinueReason = dto.discontinueReason?.let(::parseDiscontinueReason),
        discontinueNotes = dto.discontinueNotes,
        correlatedMarkers = dto.correlatedMarkers,
        dosagePeriods = dto.dosagePeriods.map(::toDomain),
        adherence = dto.adherence?.let(::toDomain),
    )

    fun toDomain(dto: MedicationDetailDto): MedicationDetail {
        val med = Medication(
            medicationId = dto.medicationId,
            drugId = dto.drugId,
            drug = dto.drug?.let(::toDomain),
            customName = dto.customName,
            status = parseStatus(dto.status),
            dose = dto.dose,
            unit = dto.unit,
            frequency = toDomain(dto.frequency),
            timeSlots = dto.timeSlots.map(::toDomain),
            protocolId = dto.protocolId,
            notes = dto.notes,
            prescribedBy = dto.prescribedBy,
            startDate = dto.startDate,
            endDate = dto.endDate,
            discontinueReason = dto.discontinueReason?.let(::parseDiscontinueReason),
            discontinueNotes = dto.discontinueNotes,
            correlatedMarkers = dto.correlatedMarkers,
            dosagePeriods = dto.dosagePeriods.map(::toDomain),
            adherence = null,
        )
        return MedicationDetail(
            medication = med,
            history = dto.history.map(::toDomain),
        )
    }

    fun toDomain(dto: DrugDto): Drug = Drug(
        drugId = dto.drugId,
        name = dto.name,
        aliases = dto.aliases,
        category = parseCategory(dto.category),
        form = parseForm(dto.form),
        defaultUnit = dto.defaultUnit,
        commonDoses = dto.commonDoses,
        imageUrl = dto.imageUrl,
        imageFallback = dto.imageFallback,
        suggestedMarkers = dto.suggestedMarkers,
        description = dto.description,
    )

    fun toDomain(dto: TodaysDoseDto): TodaysDose = TodaysDose(
        medicationId = dto.medicationId,
        drugName = dto.drugName,
        imageUrl = dto.imageUrl,
        window = parseWindow(dto.window),
        dose = dto.dose,
        unit = dto.unit,
        taken = dto.taken,
        takenAt = dto.takenAt,
    )

    fun toDomain(dto: FrequencyConfigDto): FrequencyConfig = FrequencyConfig(
        type = parseFrequencyType(dto.type),
        timesPerPeriod = dto.timesPerPeriod,
        specificDays = dto.specificDays?.map(::parseDayOfWeek),
        cycle = dto.cycle?.let {
            FrequencyConfig.CycleConfig(
                onWeeks = it.onWeeks,
                offWeeks = it.offWeeks,
                startDate = it.startDate,
            )
        },
    )

    fun toDomain(dto: TimeSlotDto): TimeSlot = TimeSlot(
        window = parseWindow(dto.window),
        dose = dto.dose,
    )

    fun toDomain(dto: DosagePeriodDto): DosagePeriod = DosagePeriod(
        dose = dto.dose,
        unit = dto.unit,
        startDate = dto.startDate,
        endDate = dto.endDate,
    )

    fun toDomain(dto: AdherenceSummaryDto): AdherenceSummary = AdherenceSummary(
        last30Days = dto.last30Days.map { AdherenceSummary.DayAdherence(it.date, it.taken) },
        percentage = dto.percentage,
    )

    fun toDomain(dto: HistoryEntryDto): MedicationHistoryEntry = MedicationHistoryEntry(
        historyId = dto.historyId,
        changeType = parseChangeType(dto.changeType),
        previousValue = dto.previousValue,
        newValue = dto.newValue,
        changedAt = dto.changedAt,
        notes = dto.notes,
    )

    // domain → wire

    fun toDto(req: CreateMedicationRequest): CreateMedicationDto = CreateMedicationDto(
        drugId = req.drugId,
        customName = req.customName,
        dose = req.dose,
        unit = req.unit,
        frequency = toDto(req.frequency),
        timeSlots = req.timeSlots.map(::toDto),
        notes = req.notes,
        prescribedBy = req.prescribedBy,
        startDate = req.startDate,
        correlatedMarkers = req.correlatedMarkers.ifEmpty { null },
    )

    fun toDto(req: UpdateMedicationRequest): UpdateMedicationDto = UpdateMedicationDto(
        customName = req.customName,
        dose = req.dose,
        unit = req.unit,
        frequency = req.frequency?.let(::toDto),
        timeSlots = req.timeSlots?.map(::toDto),
        notes = req.notes,
        prescribedBy = req.prescribedBy,
        correlatedMarkers = req.correlatedMarkers,
        changeNotes = req.changeNotes,
    )

    fun toDto(req: ChangeDoseRequest): ChangeDoseDto = ChangeDoseDto(
        dose = req.dose,
        unit = req.unit,
        startDate = req.startDate,
        changeNotes = req.changeNotes,
    )

    fun toDto(slot: TimeSlot): TimeSlotDto = TimeSlotDto(slot.window.name, slot.dose)

    fun toDto(cfg: FrequencyConfig): FrequencyConfigDto = FrequencyConfigDto(
        type = cfg.type.name,
        timesPerPeriod = cfg.timesPerPeriod,
        specificDays = cfg.specificDays?.map { it.name },
        cycle = cfg.cycle?.let {
            CycleConfigDto(
                onWeeks = it.onWeeks,
                offWeeks = it.offWeeks,
                startDate = it.startDate,
            )
        },
    )

    // enum parsers with safe fallbacks. A stale client against a newer
    // backend should never crash, just degrade.

    private fun parseStatus(s: String): MedicationStatus = runCatching {
        enumValueOf<MedicationStatus>(s)
    }.getOrDefault(MedicationStatus.ACTIVE)

    private fun parseCategory(s: String): DrugCategory = runCatching {
        enumValueOf<DrugCategory>(s)
    }.getOrDefault(DrugCategory.OTC)

    private fun parseForm(s: String): DrugForm = runCatching {
        enumValueOf<DrugForm>(s)
    }.getOrDefault(DrugForm.TABLET)

    private fun parseFrequencyType(s: String): FrequencyType = runCatching {
        enumValueOf<FrequencyType>(s)
    }.getOrDefault(FrequencyType.DAILY)

    private fun parseWindow(s: String): TimeWindow = runCatching {
        enumValueOf<TimeWindow>(s)
    }.getOrDefault(TimeWindow.MORNING)

    private fun parseDayOfWeek(s: String): DayOfWeek = runCatching {
        enumValueOf<DayOfWeek>(s)
    }.getOrDefault(DayOfWeek.MON)

    private fun parseDiscontinueReason(s: String): DiscontinueReason = runCatching {
        enumValueOf<DiscontinueReason>(s)
    }.getOrDefault(DiscontinueReason.OTHER)

    private fun parseChangeType(s: String): ChangeType = runCatching {
        enumValueOf<ChangeType>(s)
    }.getOrDefault(ChangeType.DOSE_CHANGE)
}
