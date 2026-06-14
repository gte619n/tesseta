package com.gte619n.healthfitness.core.workoutprogram;

/**
 * Non-binding per-phase (or program-level fallback) calorie/macro guidance
 * written alongside a designed program (IMPL-18 S3/R4). Display-only — the user
 * still logs food in the nutrition module (IMPL-16 stays the source of truth);
 * this never reads or writes the nutrition module's stored targets. All fields
 * are nullable: a phase may carry only a calorie window, or only a note.
 */
public record NutritionGuidance(
    Integer kcal,
    Integer proteinG,
    Integer carbsG,
    Integer fatG,
    String note
) {
    /** True when every field is empty — treat as "no guidance" and omit from the UI. */
    public boolean isEmpty() {
        return kcal == null && proteinG == null && carbsG == null && fatG == null
            && (note == null || note.isBlank());
    }
}
