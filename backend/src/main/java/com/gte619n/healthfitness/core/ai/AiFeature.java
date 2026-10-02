package com.gte619n.healthfitness.core.ai;

/**
 * The distinct AI (Gemini) features whose token/cost usage we meter
 * (IMPL-MULTIUSER-01 Pillar 2, D4). Each Gemini client class maps to exactly
 * one feature; {@link #UNKNOWN} is the fallback bucket when a recorded call
 * cannot be attributed to a known feature (keeps call COUNTS captured rather
 * than dropping the event).
 */
public enum AiFeature {
    MEAL_PHOTO,
    NUTRITION_LABEL,
    MEAL_DESCRIBE,
    LEFTOVERS,
    DRINK,
    SERVING_HINT,
    FOOD_IMAGE_GEN,
    GOAL_CHAT,
    WORKOUT_PROGRAM_CHAT,
    ADHOC_GEN,
    EXERCISE_MEDIA,
    EQUIPMENT_PARSE,
    EQUIPMENT_IMAGE,
    DRUG_IMAGE,
    EXERCISE_ENRICH,
    UNKNOWN
}
