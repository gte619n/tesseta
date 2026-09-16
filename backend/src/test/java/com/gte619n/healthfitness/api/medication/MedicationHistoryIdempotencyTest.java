package com.gte619n.healthfitness.api.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.FrequencyConfig;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationHistory;
import com.gte619n.healthfitness.core.medication.MedicationHistoryRepository;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.MedicationStatus;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.core.sync.InMemoryIdempotencyStore;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * BUG-02 regression: a medication edit's document converges (set-semantics), but
 * it also APPENDS a MedicationHistory row. That row used a random id, so an
 * offline-outbox replay of the same PUT appended a DUPLICATE history entry. The
 * row id is now derived from the Idempotency-Key, so a replay upserts the same
 * row; genuinely distinct edits (distinct keys) still get distinct rows.
 */
class MedicationHistoryIdempotencyTest {

    private static final String USER = "user-1";
    private static final String MED = "med-1";

    private final CurrentUserProvider currentUser =
        () -> new CurrentUser(USER, "u@example.com", null, null);
    private final MedicationRepository meds = mock(MedicationRepository.class);
    private final MedicationHistoryRepository history = mock(MedicationHistoryRepository.class);
    private final DrugRepository drugs = mock(DrugRepository.class);
    private final MedicationController controller = new MedicationController(
        currentUser, meds, history, mock(com.gte619n.healthfitness.core.medication.AdherenceRepository.class),
        drugs, new SyncWriteContext(new InMemoryIdempotencyStore()), mock(SyncChangeNotifier.class));

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withIdempotencyKey(String key) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (key != null) {
            req.addHeader(SyncWriteContext.IDEMPOTENCY_KEY_HEADER, key);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private Medication medAt(double dose) {
        return new Medication(
            USER, MED, "drug-1", null, MedicationStatus.ACTIVE,
            dose, "mg", FrequencyConfig.daily(1), List.of(), null, null, null,
            LocalDate.of(2026, 1, 1), null, null, null, null,
            List.of(), Instant.now(), Instant.now());
    }

    @Test
    void replayedDoseChangeReusesTheSameHistoryRowId() {
        when(meds.findById(USER, MED)).thenReturn(Optional.of(medAt(10)));
        when(drugs.findById(any())).thenReturn(Optional.empty());

        var body = new MedicationController.UpdateMedicationRequest(
            null, 20.0, "mg", null, null, null, null, null, null, null, null, "bumped");

        // First submit and its replay carry the SAME Idempotency-Key.
        withIdempotencyKey("key-abc");
        controller.update(MED, body);
        withIdempotencyKey("key-abc");
        controller.update(MED, body);

        ArgumentCaptor<MedicationHistory> captor = ArgumentCaptor.forClass(MedicationHistory.class);
        verify(history, times(2)).save(captor.capture());
        var ids = captor.getAllValues().stream().map(MedicationHistory::historyId).distinct().toList();
        // Same id both times → the second save upserts the first row, not a dup.
        assertThat(ids).containsExactly("key-abc:dose");
    }

    @Test
    void distinctEditsGetDistinctHistoryRows() {
        when(meds.findById(USER, MED)).thenReturn(Optional.of(medAt(10)), Optional.of(medAt(20)));
        when(drugs.findById(any())).thenReturn(Optional.empty());

        withIdempotencyKey("key-1");
        controller.update(MED, new MedicationController.UpdateMedicationRequest(
            null, 20.0, "mg", null, null, null, null, null, null, null, null, null));
        withIdempotencyKey("key-2");
        controller.update(MED, new MedicationController.UpdateMedicationRequest(
            null, 30.0, "mg", null, null, null, null, null, null, null, null, null));

        ArgumentCaptor<MedicationHistory> captor = ArgumentCaptor.forClass(MedicationHistory.class);
        verify(history, times(2)).save(captor.capture());
        var ids = captor.getAllValues().stream().map(MedicationHistory::historyId).distinct().toList();
        assertThat(ids).containsExactlyInAnyOrder("key-1:dose", "key-2:dose");
    }
}
