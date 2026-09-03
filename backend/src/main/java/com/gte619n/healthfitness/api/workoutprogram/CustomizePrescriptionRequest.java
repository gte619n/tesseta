package com.gte619n.healthfitness.api.workoutprogram;

import java.time.LocalDate;

/**
 * Body for the in-workout swap / rep-set edit (#4). Identifies one prescription
 * slot by {@code (blockId, orderIndex)} and carries the change; any change field
 * left null is untouched. {@code applyToProgram} pushes the same edit to the
 * program template's matching day and to future PLANNED sessions of that day.
 *
 * <p>{@code phaseId}/{@code dayId}/{@code date} are the day reference used to
 * materialize a client-minted ad-hoc session that the server hasn't seen yet
 * (offline-first run-as-today), mirroring the completion PUT.
 */
public record CustomizePrescriptionRequest(
    String blockId,
    int orderIndex,
    boolean applyToProgram,
    String exerciseId,
    Integer sets,
    Integer repsMin,
    Integer repsMax,
    String phaseId,
    String dayId,
    LocalDate date
) {}
