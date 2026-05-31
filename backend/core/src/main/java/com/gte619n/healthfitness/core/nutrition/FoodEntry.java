package com.gte619n.healthfitness.core.nutrition;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One logged food on a given day and meal. {@code macros} is a snapshot frozen
 * at log time so editing the underlying catalog food never rewrites history.
 */
public record FoodEntry(
    String userId,
    LocalDate date,
    String entryId,
    MealType meal,
    String foodId,
    String foodName,
    String servingLabel,
    Double servingGrams,
    Double quantity,
    Macros macros,
    String photoRef,
    EntrySource source,
    Instant createdAt,
    Instant updatedAt
) {}
