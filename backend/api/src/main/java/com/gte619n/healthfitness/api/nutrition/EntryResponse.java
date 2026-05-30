package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.MealType;
import java.time.LocalDate;

/** Wire representation of a {@link FoodEntry}. */
public record EntryResponse(
    String entryId,
    LocalDate date,
    MealType meal,
    String foodId,
    String foodName,
    String servingLabel,
    Double servingGrams,
    Double quantity,
    MacrosDto macros,
    EntrySource source
) {
    public static EntryResponse from(FoodEntry e) {
        return new EntryResponse(
            e.entryId(),
            e.date(),
            e.meal(),
            e.foodId(),
            e.foodName(),
            e.servingLabel(),
            e.servingGrams(),
            e.quantity(),
            MacrosDto.from(e.macros()),
            e.source()
        );
    }
}
