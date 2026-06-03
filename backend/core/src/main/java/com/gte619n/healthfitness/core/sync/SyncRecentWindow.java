package com.gte619n.healthfitness.core.sync;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * Server-honored recent-window bound for the unified delta read (IMPL-AND-20
 * #37 / D14). When the client's <em>first</em> sync supplies a {@code recentSince}
 * date, the server bounds the <b>heavy time-series</b> collections to documents
 * whose sample/effective date is on or after that bound, while CRUD domains
 * (medications, goals, protocols, locations, nutrition targets, the user
 * profile, …) always return in full.
 *
 * <p>This is a pure emission <em>filter</em> layered on top of the normal
 * {@code (updatedAt, collection, id)} cursor ordering — it never changes the
 * cursor key. Consequently the windowed first pass and a later unbounded
 * backfill (the client simply omits {@code recentSince}) stay consistent: the
 * backfill re-enumerates from an empty cursor and surfaces the older heavy docs
 * the window skipped, and re-applying the already-synced rows is an idempotent
 * last-write-wins no-op on the client. No change is ever skipped or duplicated.
 *
 * <h2>Heavy collections and their effective-date field</h2>
 * <pre>
 *   bloodReadings            sampleDate   (LocalDate)
 *   bodyComposition          sampleTime   (Instant/Timestamp → its date)
 *   dailyMetrics             date         (LocalDate)
 *   nutritionDailyLogs       date         (LocalDate)
 *   nutritionDays/entries    date         (LocalDate)
 *   weeklyWorkoutAggregates  weekStart    (LocalDate)
 * </pre>
 *
 * <p>A heavy doc with no resolvable effective date is conservatively
 * <b>included</b> (the window must never hide a row whose date we cannot read).
 */
public record SyncRecentWindow(LocalDate since) {

    /**
     * Heavy time-series collections (by emitted {@code collection} name) mapped
     * to the document field carrying their sample/effective date. Anything not
     * in this map is a CRUD domain and is never windowed.
     */
    public static final Map<String, String> HEAVY_DATE_FIELDS = Map.of(
        "bloodReadings", "sampleDate",
        "bodyComposition", "sampleTime",
        "dailyMetrics", "date",
        "nutritionDailyLogs", "date",
        "nutritionDays/entries", "date",
        "weeklyWorkoutAggregates", "weekStart"
    );

    /** The set of collections this window bounds; all others pass unfiltered. */
    public static Set<String> heavyCollections() {
        return HEAVY_DATE_FIELDS.keySet();
    }

    public SyncRecentWindow {
        if (since == null) {
            throw new IllegalArgumentException("recent-window since date is required");
        }
    }

    /** True when {@code collection} is a heavy time-series bounded by this window. */
    public boolean isHeavy(String collection) {
        return HEAVY_DATE_FIELDS.containsKey(collection);
    }

    /**
     * Whether a change should be emitted under this window.
     *
     * @param collection    the emitted collection name
     * @param effectiveDate the doc's sample/effective date, or {@code null} when
     *                      the collection is not heavy or the date is unreadable
     * @return {@code true} to emit — always for CRUD domains, and for heavy docs
     *         on or after {@link #since} (or with an unreadable date)
     */
    public boolean includes(String collection, LocalDate effectiveDate) {
        if (!isHeavy(collection)) {
            return true;
        }
        if (effectiveDate == null) {
            // Can't bound it — never hide a heavy row we can't date.
            return true;
        }
        return !effectiveDate.isBefore(since);
    }
}
