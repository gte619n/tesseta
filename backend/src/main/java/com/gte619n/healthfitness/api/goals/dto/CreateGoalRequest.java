package com.gte619n.healthfitness.api.goals.dto;

import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalSource;
import java.time.LocalDate;

public record CreateGoalRequest(
    String id,              // optional client-minted UUID (IMPL-AND-20 D7); null ⇒ server-generated
    String title,
    String description,
    GoalDomain domain,
    LocalDate startDate,
    LocalDate targetDate,
    GoalSource source
) {}
