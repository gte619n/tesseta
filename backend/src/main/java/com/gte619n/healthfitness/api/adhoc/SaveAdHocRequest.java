package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.core.adhoc.AdHocSource;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.util.List;

/**
 * Body of {@code POST /api/me/adhoc-workouts} — persist a template (IMPL-ADHOC-01).
 * The workout body is the core {@link WorkoutDay} record (Jackson deserializes it
 * directly, mirroring the program create request's phases).
 */
public record SaveAdHocRequest(
    String title,
    String summary,
    AdHocSource source,
    String prompt,
    EquipmentContextDto equipmentContext,
    Integer targetDurationMinutes,
    List<String> tags,
    boolean pinned,
    WorkoutDay day
) {}
