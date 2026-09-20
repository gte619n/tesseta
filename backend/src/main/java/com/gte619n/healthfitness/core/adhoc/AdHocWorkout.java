package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.util.List;

/**
 * A reusable, purpose-driven ad-hoc workout template (IMPL-ADHOC-01). Stored at
 * {@code users/{userId}/adhocWorkouts/{adhocId}} ({@code adhocId} = "aw_" + 12).
 *
 * <p>The workout body reuses {@link WorkoutDay} (blocks → prescriptions); the
 * day's {@code dayOfWeek}/{@code locationId} are unused (an ad-hoc workout is
 * not pinned to a calendar day or a saved gym). Each "run" spawns an independent
 * session under this template's {@code sessions} subcollection (D2), and runs
 * are logged to history/e1RM/stats but never drive the program's forward
 * progression engine (D6).
 *
 * <p>{@code runCount} and {@code lastPerformedAt} are denormalized run counters
 * kept idempotently by {@code AdHocSessionService} for library sort
 * (recent / most-done). {@code pinned} + {@code tags} drive favourites and
 * filtering (D7). Archival is the {@code syncStatus} tombstone (D12), handled in
 * the repository like every other soft-deleted collection.
 */
public record AdHocWorkout(
    String userId,
    String adhocId,
    String title,
    String summary,                 // one-line "what/why"; nullable
    AdHocSource source,
    String prompt,                  // original NL prompt (for regenerate/reference); nullable
    EquipmentContext equipmentContext,
    Integer targetDurationMinutes,  // what the user asked for; nullable
    Integer estimatedDurationSeconds, // computed by WorkoutDurationEstimator
    List<String> tags,
    boolean pinned,
    WorkoutDay day,                 // the workout body
    int runCount,
    Instant lastPerformedAt,        // nullable until first run
    Instant createdAt,
    Instant updatedAt
) {
    public AdHocWorkout {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
