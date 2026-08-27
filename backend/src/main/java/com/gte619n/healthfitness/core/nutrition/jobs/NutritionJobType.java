package com.gte619n.healthfitness.core.nutrition.jobs;

/**
 * The kinds of durable background work in the nutrition pipeline (Tier 3). Each
 * value is one unit of work that used to run on a detached
 * {@code CompletableFuture.runAsync} — and so was lost whenever the Cloud Run
 * instance was throttled, scaled in or redeployed mid-flight. As a
 * {@link NutritionJob} on a durable queue the work survives instance death and
 * is retried until it succeeds (or is finally marked failed).
 */
public enum NutritionJobType {

    /** Generate the studio image for a catalog food. */
    FOOD_IMAGE,

    /** Generate the finished-meal ("hero") image for a composite food entry. */
    ENTRY_IMAGE,

    /** Generate the plated-dish image for a saved meal. */
    SAVED_MEAL_IMAGE,

    /** Analyze a captured meal/product photo and finalize its placeholder entry. */
    MEAL_ANALYSIS,

    /** Resolve a described meal and finalize its placeholder entry. */
    DESCRIPTION_ANALYSIS
}
