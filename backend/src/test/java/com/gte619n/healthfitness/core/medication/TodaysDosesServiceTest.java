package com.gte619n.healthfitness.core.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Protects the scheduling/taken-status logic extracted from TodaysDosesController
// into TodaysDosesService (ADR-0020, D10), shared by the first-party endpoint and
// GET /v1/doses.
class TodaysDosesServiceTest {

    private static final String USER = "user-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 27); // a Monday

    private final MedicationRepository meds = mock(MedicationRepository.class);
    private final AdherenceRepository adherence = mock(AdherenceRepository.class);
    private final DrugRepository drugs = mock(DrugRepository.class);
    private final TodaysDosesService service = new TodaysDosesService(meds, adherence, drugs);

    @BeforeEach
    void stubDrugs() {
        when(drugs.findByIds(anyList())).thenReturn(Map.of());
    }

    private static Medication daily(String id) {
        return Medication.create(USER, id, "drug-" + id, 100, "mg",
            FrequencyConfig.daily(1), List.of(new TimeSlot(TimeWindow.MORNING, 100)),
            LocalDate.of(2026, 1, 1), List.of());
    }

    @Test
    void dailyMedicationAppearsAsAScheduledDose() {
        when(meds.findByUserAndStatus(USER, MedicationStatus.ACTIVE))
            .thenReturn(List.of(daily("m1")));
        when(adherence.findByUserAndDateRange(USER, TODAY, TODAY)).thenReturn(List.of());

        List<TodaysDose> doses = service.forDate(USER, TODAY);

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).medicationId()).isEqualTo("m1");
        assertThat(doses.get(0).window()).isEqualTo(TimeWindow.MORNING);
        assertThat(doses.get(0).taken()).isFalse();
    }

    @Test
    void prnMedicationIsNeverScheduled() {
        Medication prn = Medication.create(USER, "m2", "drug-2", 50, "mg",
            FrequencyConfig.prn(), List.of(new TimeSlot(TimeWindow.EVENING, 50)),
            LocalDate.of(2026, 1, 1), List.of());
        when(meds.findByUserAndStatus(USER, MedicationStatus.ACTIVE)).thenReturn(List.of(prn));
        when(adherence.findByUserAndDateRange(USER, TODAY, TODAY)).thenReturn(List.of());

        assertThat(service.forDate(USER, TODAY)).isEmpty();
    }

    @Test
    void takenStatusIsReflectedFromTheAdherenceLog() {
        Instant takenAt = Instant.parse("2026-07-27T08:15:00Z");
        when(meds.findByUserAndStatus(USER, MedicationStatus.ACTIVE))
            .thenReturn(List.of(daily("m1")));
        when(adherence.findByUserAndDateRange(USER, TODAY, TODAY)).thenReturn(List.of(
            new AdherenceLog(USER, "m1", TODAY,
                List.of(new DoseLog(TimeWindow.MORNING, takenAt, 100)), null)));

        List<TodaysDose> doses = service.forDate(USER, TODAY);

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).taken()).isTrue();
        assertThat(doses.get(0).takenAt()).isEqualTo(takenAt);
    }

    @Test
    void missedDoseDoesNotCountAsTaken() {
        // IMPL-21: an auto-recorded missed marker must NOT flip the dose to taken.
        when(meds.findByUserAndStatus(USER, MedicationStatus.ACTIVE))
            .thenReturn(List.of(daily("m1")));
        when(adherence.findByUserAndDateRange(USER, TODAY, TODAY)).thenReturn(List.of(
            new AdherenceLog(USER, "m1", TODAY,
                List.of(new DoseLog(TimeWindow.MORNING, null, 100, true)), null)));

        List<TodaysDose> doses = service.forDate(USER, TODAY);

        assertThat(doses).hasSize(1);
        assertThat(doses.get(0).taken()).isFalse();
    }

    @Test
    void weeklyMedicationOnlyAppearsOnItsDays() {
        // TODAY is a Monday; schedule only Tuesday -> not today.
        Medication tueOnly = Medication.create(USER, "m3", "drug-3", 10, "mg",
            FrequencyConfig.weekly(List.of(DayOfWeek.TUE)),
            List.of(new TimeSlot(TimeWindow.MORNING, 10)), LocalDate.of(2026, 1, 1), List.of());
        when(meds.findByUserAndStatus(USER, MedicationStatus.ACTIVE)).thenReturn(List.of(tueOnly));
        when(adherence.findByUserAndDateRange(USER, TODAY, TODAY)).thenReturn(List.of());

        assertThat(service.forDate(USER, TODAY)).isEmpty();
    }
}
