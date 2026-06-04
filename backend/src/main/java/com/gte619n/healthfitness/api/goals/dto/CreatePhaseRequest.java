package com.gte619n.healthfitness.api.goals.dto;

import java.time.LocalDate;

public record CreatePhaseRequest(
    String id,                  // optional client-minted UUID (IMPL-AND-20 D7); null ⇒ server-generated
    String title,
    String description,
    LocalDate targetStartDate,
    LocalDate targetEndDate
) {}
