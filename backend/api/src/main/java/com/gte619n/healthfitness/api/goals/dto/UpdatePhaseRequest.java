package com.gte619n.healthfitness.api.goals.dto;

import java.time.LocalDate;

public record UpdatePhaseRequest(
    String title,
    String description,
    LocalDate targetStartDate,
    LocalDate targetEndDate
) {}
