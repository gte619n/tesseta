package com.gte619n.healthfitness.core.exercise;

/** Catalog lifecycle, mirroring EquipmentStatus. Only PUBLISHED exercises are
 *  offered to users and the workout-program generator. */
public enum ExerciseStatus {
    DRAFT, PUBLISHED, ARCHIVED
}
