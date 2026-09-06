package com.gte619n.healthfitness.core.user;

/**
 * Biological sex, used only for the Mifflin-St Jeor resting-metabolic-rate
 * estimate that seeds the progression engine's maintenance-calorie cold-start
 * (IMPL-PROG-01 M3). Distinct from gender identity; captured because the BMR
 * equation has sex-specific constants.
 */
public enum BiologicalSex {
    MALE, FEMALE
}
