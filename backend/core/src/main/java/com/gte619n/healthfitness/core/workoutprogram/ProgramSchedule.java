package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import java.util.List;
import java.util.Map;

/** Which weekdays the user trains and the gym for each. */
public record ProgramSchedule(
    List<DayOfWeek> trainingDays,
    Map<DayOfWeek, String> dayLocations
) {}
