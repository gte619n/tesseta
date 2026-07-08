package com.gte619n.healthfitness.core.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #9 — flagging a demo frame as bad flags it for the admin (clears the reviewed
 * sign-off) while keeping the media served (mediaStatus untouched).
 */
class ExerciseServiceFlagFrameTest {

    /** Approved media, already signed off as reviewed. */
    private static Exercise approvedAndReviewed(String id) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, true, List.of());
    }

    @Test
    void flaggingAFrameKeepsItServedButMarksItForReview() {
        InMemoryExerciseRepository repo = new InMemoryExerciseRepository();
        repo.save(approvedAndReviewed("ex_squat"));
        ExerciseService service = new ExerciseService(repo, true);

        Exercise flagged = service.flagFrame("ex_squat", "bottom", "wrong knee angle");

        // Still served — media stays APPROVED, not pulled from the pool.
        assertEquals(ExerciseMediaStatus.APPROVED, flagged.mediaStatus());
        // Surfaced to the admin as needing attention.
        assertFalse(flagged.reviewed());
        assertEquals(ExerciseMediaStatus.APPROVED,
            repo.findById("ex_squat").orElseThrow().mediaStatus());
        assertFalse(repo.findById("ex_squat").orElseThrow().reviewed());
    }
}
