package com.gte619n.healthfitness.core.ai;

import java.util.List;
import java.util.Optional;

/**
 * Port for persisting AI usage (IMPL-MULTIUSER-01 Pillar 2). The implementation
 * writes the raw event and atomically folds it into the per-user and global
 * monthly rollups, and serves the rollup reads that back the admin dashboard
 * (no raw-event scans on the hot path).
 */
public interface AiUsageStore {

    /**
     * Persist one event AND increment both the per-user and the global monthly
     * rollups for the event's month. Implementations use atomic field
     * increments so concurrent calls never lose an update.
     */
    void recordEvent(AiUsageEvent event);

    /**
     * The per-user rollup for a month, or empty if the user had no usage that
     * month.
     *
     * @param userId    the user id
     * @param yearMonth {@code yyyy-MM}
     */
    Optional<AiUsageSummary> monthlyForUser(String userId, String yearMonth);

    /**
     * The global (all-users) rollup for a month, or empty if there was no usage.
     *
     * @param yearMonth {@code yyyy-MM}
     */
    Optional<AiUsageSummary> monthlyGlobal(String yearMonth);

    /**
     * The top-spending users for a month, descending by cost, capped at
     * {@code limit}. Backed by the per-user monthly rollups.
     *
     * @param yearMonth {@code yyyy-MM}
     * @param limit     max rows
     */
    List<AiUsageSummary> topSpenders(String yearMonth, int limit);
}
