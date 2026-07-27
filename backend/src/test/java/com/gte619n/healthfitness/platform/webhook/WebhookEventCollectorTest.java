package com.gte619n.healthfitness.platform.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.dexa.DexaScanRepository;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.TimeSlot;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Verifies the webhook change-detection (ADR-0020, D19): the collector only emits
// an event when the client both subscribed to it AND holds the required scope,
// and only for changes inside the (since, until] window.
class WebhookEventCollectorTest {

    private static final String USER = "user-1";
    private static final Instant SINCE = Instant.parse("2020-01-01T00:00:00Z");
    private final Instant until = Instant.now().plusSeconds(3600);

    private final MedicationRepository medications = mock(MedicationRepository.class);
    private final AdherenceRepository adherence = mock(AdherenceRepository.class);
    private final WorkoutProgramRepository programs = mock(WorkoutProgramRepository.class);
    private final ScheduledWorkoutRepository scheduled = mock(ScheduledWorkoutRepository.class);
    private final NutritionDailyLogRepository nutrition = mock(NutritionDailyLogRepository.class);
    private final BloodReadingRepository blood = mock(BloodReadingRepository.class);
    private final DexaScanRepository dexa = mock(DexaScanRepository.class);
    private final DailyMetricRepository dailyMetrics = mock(DailyMetricRepository.class);

    private final WebhookEventCollector collector = new WebhookEventCollector(
        medications, adherence, programs, scheduled, nutrition, blood, dexa, dailyMetrics);

    private Medication activeMed() {
        return Medication.create(USER, "m1", "d1", 100, "mg", FrequencyConfig.daily(1),
            List.of(new TimeSlot(TimeWindow.MORNING, 100)), LocalDate.of(2026, 1, 1), List.of());
    }

    @Test
    void medicationChangeCollectedWhenSubscribedAndScoped() {
        when(medications.findByUser(USER)).thenReturn(List.of(activeMed()));

        List<WebhookEvent> events = collector.collect(
            USER, Set.of("medications:read"), Set.of("medication.changed"), SINCE, until);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("medication.changed");
        assertThat(events.get(0).href()).isEqualTo("/v1/medications/m1");
    }

    @Test
    void nothingCollectedWithoutTheRequiredScope() {
        when(medications.findByUser(USER)).thenReturn(List.of(activeMed()));

        assertThat(collector.collect(
            USER, Set.of("workouts:read"), Set.of("medication.changed"), SINCE, until))
            .isEmpty();
    }

    @Test
    void nothingCollectedWhenTheEventTypeIsNotSubscribed() {
        when(medications.findByUser(USER)).thenReturn(List.of(activeMed()));

        assertThat(collector.collect(
            USER, Set.of("medications:read"), Set.of("workout.completed"), SINCE, until))
            .isEmpty();
    }

    @Test
    void doseLoggedCollectedFromAdherenceWithinWindow() {
        Instant takenAt = Instant.now();
        when(adherence.findByUserAndDateRange(eq(USER), any(), any())).thenReturn(List.of(
            new AdherenceLog(USER, "m1", LocalDate.now(),
                List.of(new DoseLog(TimeWindow.MORNING, takenAt, 100)), null)));

        List<WebhookEvent> events = collector.collect(
            USER, Set.of("medications:read"), Set.of("dose.logged"), SINCE, until);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("dose.logged");
    }

    @Test
    void doseOutsideTheWindowIsIgnored() {
        Instant longAgo = Instant.parse("2019-01-01T00:00:00Z"); // before SINCE
        when(adherence.findByUserAndDateRange(eq(USER), any(), any())).thenReturn(List.of(
            new AdherenceLog(USER, "m1", LocalDate.now(),
                List.of(new DoseLog(TimeWindow.MORNING, longAgo, 100)), null)));

        assertThat(collector.collect(
            USER, Set.of("medications:read"), Set.of("dose.logged"), SINCE, until))
            .isEmpty();
    }
}
