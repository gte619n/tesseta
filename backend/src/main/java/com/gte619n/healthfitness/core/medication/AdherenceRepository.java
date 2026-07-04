package com.gte619n.healthfitness.core.medication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Repository for adherence logs.
 */
public interface AdherenceRepository {
    Optional<AdherenceLog> findByDate(String userId, String medicationId, LocalDate date);
    List<AdherenceLog> findByDateRange(String userId, String medicationId, LocalDate from, LocalDate to);
    List<AdherenceLog> findByUserAndDateRange(String userId, LocalDate from, LocalDate to);
    void save(AdherenceLog log);
    void deleteByDate(String userId, String medicationId, LocalDate date);

    /**
     * Atomically add-or-replace the dose for a (medication, date, window):
     * replaces any existing dose for the same window, appends the new one, and
     * persists. Two concurrent logs for <em>different</em> windows on the same day
     * must both survive.
     *
     * <p>The default here is the historical read-modify-write and is NOT atomic —
     * it is fine for the single-threaded in-memory test fakes but the Firestore
     * implementation overrides it with a transaction, which is what actually
     * prevents the lost update under concurrency.
     */
    default AdherenceLog upsertDose(
        String userId, String medicationId, LocalDate date, DoseLog dose, String notes) {
        List<DoseLog> doses = findByDate(userId, medicationId, date)
            .map(existing -> new ArrayList<>(existing.doses()))
            .orElseGet(ArrayList::new);
        doses.removeIf(d -> d.window() == dose.window());
        doses.add(dose);
        AdherenceLog log = new AdherenceLog(userId, medicationId, date, doses, notes);
        save(log);
        return log;
    }

    /**
     * Atomically remove the dose for a (medication, date, window). If no doses
     * remain the day's log is tombstoned. Returns the resulting log, or empty if
     * the log was removed or never existed. The Firestore implementation overrides
     * this with a transaction; the default is the non-atomic read-modify-write.
     */
    default Optional<AdherenceLog> removeDose(
        String userId, String medicationId, LocalDate date, TimeWindow window) {
        AdherenceLog existing = findByDate(userId, medicationId, date).orElse(null);
        if (existing == null) {
            return Optional.empty();
        }
        List<DoseLog> doses = new ArrayList<>(existing.doses());
        doses.removeIf(d -> d.window() == window);
        if (doses.isEmpty()) {
            deleteByDate(userId, medicationId, date);
            return Optional.empty();
        }
        AdherenceLog updated =
            new AdherenceLog(userId, medicationId, date, doses, existing.notes());
        save(updated);
        return Optional.of(updated);
    }
}
