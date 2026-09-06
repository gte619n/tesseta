package com.gte619n.healthfitness.api.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.TimeSlot;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.sync.InMemoryIdempotencyStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Regression for the dose-log idempotency scope. It was {@code (medication, date)}
 * — window excluded — so for a twice-a-day medication the second window's log
 * carried the same replay identity as the first and was silently no-op'd (the
 * phone's mirror showed it taken; the server never recorded it). The scope now
 * includes the window, which also protects legacy clients that still send the
 * old {@code (med,date)}-shaped Idempotency-Key for both windows.
 */
class AdherenceControllerIdempotencyTest {

    private static final String USER = "user-1";
    private static final String MED = "med-1";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);

    private final CurrentUserProvider currentUser =
        () -> new CurrentUser(USER, "u@example.com", null, null);
    private final MedicationRepository meds = mock(MedicationRepository.class);
    private final AdherenceRepository adherence = mock(AdherenceRepository.class);
    private final AdherenceController controller = new AdherenceController(
        currentUser, meds, adherence, mock(DrugRepository.class),
        mock(MetricChangedPublisher.class),
        new SyncWriteContext(new InMemoryIdempotencyStore()),
        mock(SyncChangeNotifier.class));

    // The day's log as the fake repository accumulates it.
    private final List<DoseLog> dayDoses = new ArrayList<>();

    @BeforeEach
    void stubRepositories() {
        Medication med = Medication.create(USER, MED, "drug-1", 100, "mg",
            FrequencyConfig.daily(2),
            List.of(new TimeSlot(TimeWindow.MORNING, 100), new TimeSlot(TimeWindow.EVENING, 100)),
            LocalDate.of(2026, 1, 1), List.of());
        when(meds.findById(USER, MED)).thenReturn(Optional.of(med));
        when(adherence.upsertDose(eq(USER), eq(MED), eq(DAY), any(), isNull()))
            .thenAnswer(inv -> {
                dayDoses.add(inv.getArgument(3));
                return new AdherenceLog(USER, MED, DAY, List.copyOf(dayDoses), null);
            });
        when(adherence.findByDate(USER, MED, DAY))
            .thenAnswer(inv -> Optional.of(new AdherenceLog(USER, MED, DAY, List.copyOf(dayDoses), null)));
    }

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void requestWithIdempotencyKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Idempotency-Key", key);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void log(TimeWindow window) {
        controller.logDose(MED, new AdherenceController.LogDoseRequest(DAY, window, null, null, null));
    }

    @Test
    void bothWindowsRecordEvenWhenALegacyClientSharesOneKey() {
        // Legacy (med,date)-shaped key: identical for both of the day's doses.
        requestWithIdempotencyKey("adherence:med-1:2026-09-07");
        log(TimeWindow.MORNING);
        requestWithIdempotencyKey("adherence:med-1:2026-09-07");
        log(TimeWindow.EVENING);

        ArgumentCaptor<DoseLog> written = ArgumentCaptor.forClass(DoseLog.class);
        verify(adherence, times(2)).upsertDose(eq(USER), eq(MED), eq(DAY), written.capture(), isNull());
        assertThat(written.getAllValues())
            .extracting(DoseLog::window)
            .containsExactly(TimeWindow.MORNING, TimeWindow.EVENING);
    }

    @Test
    void trueDuplicateReplayOfTheSameWindowIsANoOp() {
        requestWithIdempotencyKey("adherence:med-1:2026-09-07:MORNING");
        log(TimeWindow.MORNING);
        requestWithIdempotencyKey("adherence:med-1:2026-09-07:MORNING");
        log(TimeWindow.MORNING);

        // The retry is served from the replay guard (current day state), not re-written.
        verify(adherence, times(1)).upsertDose(eq(USER), eq(MED), eq(DAY), any(), isNull());
        verify(adherence).findByDate(USER, MED, DAY);
    }
}
