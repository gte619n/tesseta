package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import java.util.List;

/** One session in a phase's weekly microcycle, pinned to a day and a gym. */
public record WorkoutDay(
    String dayId,
    String label,
    DayOfWeek dayOfWeek,
    String locationId,          // the gym — drives the equipment constraint
    int orderIndex,
    List<Block> blocks
) {}
