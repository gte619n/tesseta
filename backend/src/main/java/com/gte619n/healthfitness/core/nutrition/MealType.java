package com.gte619n.healthfitness.core.nutrition;

/** Meal grouping within a nutrition day. */
public enum MealType {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK,
    /**
     * Alcoholic drinks logged from Drink Mode (IMPL-DRINK-01). Surfaced as its own
     * day-view section only when a day actually has drink entries, so it never
     * clutters the day view for non-drinkers.
     */
    DRINKS
}
