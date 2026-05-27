package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalSource;
import java.time.LocalDate;

public record CreateGoalRequest(
    String title,
    String description,
    GoalDomain domain,
    LocalDate startDate,
    LocalDate targetDate,
    GoalSource source
) {}
