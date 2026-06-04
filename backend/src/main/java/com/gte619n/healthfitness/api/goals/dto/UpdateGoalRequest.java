package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import java.time.LocalDate;

public record UpdateGoalRequest(
    String title,
    String description,
    GoalDomain domain,
    GoalStatus status,
    LocalDate startDate,
    LocalDate targetDate
) {}
