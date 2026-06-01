package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * Fills in the structured metadata for an exercise we only know the name of
 * (the IMPL-15 history import seeds 352 name-only movements). Implementations
 * live in {@code integrations} (Gemini); {@code core} depends only on this port.
 *
 * <p>Equipment is returned as <em>names</em> ({@code equipmentNameGroups}, an
 * any-of group per requirement) rather than ids — the importer resolves names
 * to Equipment-catalog ids, exactly as {@code ExerciseCatalogSeeder} does.
 */
public interface ExerciseMetadataEnricher {

    /** Enrich a single exercise by name. Never throws — returns {@link #empty(String)} on failure. */
    Enrichment enrich(String exerciseName);

    /**
     * Structured metadata for one exercise. Mirrors {@link ExerciseEdit} minus
     * the resolved equipment ids: {@code equipmentNameGroups} carries equipment
     * by name so the caller can resolve + report unresolved.
     */
    record Enrichment(
        MovementPattern movementPattern,
        List<String> primaryMuscles,
        List<String> secondaryMuscles,
        Laterality laterality,
        Mechanic mechanic,
        String description,
        List<String> formCues,
        List<BlockType> suitableBlockTypes,
        RepRange defaultRepRange,
        boolean isTimed,
        List<List<String>> equipmentNameGroups
    ) {}

    /** A neutral, safe default used when enrichment is unavailable or fails. */
    static Enrichment empty(String name) {
        return new Enrichment(
            MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(),
            List.of(BlockType.MAIN), null, false, List.of());
    }
}
