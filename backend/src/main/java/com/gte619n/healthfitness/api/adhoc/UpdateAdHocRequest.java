package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.List;

/**
 * Body of {@code PATCH /api/me/adhoc-workouts/{adhocId}} (IMPL-ADHOC-01). Null
 * fields leave the existing value unchanged; a supplied {@code day} replaces the
 * workout body (re-normalized, estimate recomputed).
 */
public record UpdateAdHocRequest(
    String title,
    String summary,
    List<String> tags,
    Boolean pinned,
    Integer targetDurationMinutes,
    WorkoutDay day
) {}
