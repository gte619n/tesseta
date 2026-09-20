package com.gte619n.healthfitness.core.adhoc;

import java.util.List;
import java.util.Optional;

/** Persistence for ad-hoc workout templates (IMPL-ADHOC-01). */
public interface AdHocWorkoutRepository {
    Optional<AdHocWorkout> findById(String userId, String adhocId);

    /** Active (non-archived) templates for the user. */
    List<AdHocWorkout> findByUser(String userId);

    /**
     * Like {@link #findByUser} but includes archived (tombstoned) templates — the
     * "Archived" library filter (D12) and any full-history read.
     */
    List<AdHocWorkout> findByUserIncludingArchived(String userId);

    void save(AdHocWorkout workout);

    /** Soft-delete (tombstone) — sets syncStatus=ARCHIVED + bumps updatedAt. */
    void archive(String userId, String adhocId);

    /** Clear the tombstone — restore an archived template (D12). */
    void restore(String userId, String adhocId);
}
