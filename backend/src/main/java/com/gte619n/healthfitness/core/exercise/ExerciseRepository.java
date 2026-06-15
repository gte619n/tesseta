package com.gte619n.healthfitness.core.exercise;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExerciseRepository {
    Optional<Exercise> findById(String exerciseId);
    List<Exercise> findByIds(Collection<String> exerciseIds);
    /** PUBLISHED + APPROVED-media exercises, optionally filtered. */
    List<Exercise> findPublished(String search, MovementPattern pattern, BlockType block, String muscle);
    /** Every exercise regardless of status — admin catalog. */
    List<Exercise> findAll();
    /** Exercises whose media is awaiting review — admin review queue. */
    List<Exercise> findByMediaStatus(ExerciseMediaStatus mediaStatus);
    /** Exercises in a given frame-plan status — used by the plan backfill job (IMPL-19). */
    List<Exercise> findByPlanStatus(ExerciseMediaStatus planStatus);
    void save(Exercise exercise);
    void delete(String exerciseId);
}
