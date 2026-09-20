package com.gte619n.healthfitness.core.adhoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.testsupport.adhoc.InMemoryAdHocWorkoutRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CRUD + archival/restore + normalization/estimate (IMPL-ADHOC-01 Phase 1). */
class AdHocWorkoutServiceTest {

    private static final String USER = "u-adhoc";

    private InMemoryAdHocWorkoutRepository repo;
    private AdHocWorkoutService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryAdHocWorkoutRepository();
        service = new AdHocWorkoutService(repo);
    }

    private static WorkoutDay dayWithOneExercise() {
        Prescription rx = new Prescription("ex_bench", 0, 3, 8, 12, null, null, 90, null, null, null, null);
        return new WorkoutDay(null, "Full body", null, null, 0,
            List.of(new Block(null, BlockType.MAIN, "Main", 0, List.of(rx))));
    }

    private AdHocWorkout createBasic() {
        return service.create(new AdHocWorkout(
            USER, null, "Hotel workout", "quick full body", AdHocSource.AI_GENERATED, "30 min hotel gym",
            EquipmentContext.empty(), 30, null, List.of("Travel"), false, dayWithOneExercise(),
            0, null, null, null));
    }

    @Test
    void createMintsIdNormalizesAndEstimates() {
        AdHocWorkout w = createBasic();
        assertThat(w.adhocId()).startsWith("aw_");
        assertThat(w.day().dayId()).startsWith("wd_");
        assertThat(w.day().blocks().get(0).blockId()).startsWith("bk_");
        assertThat(w.estimatedDurationSeconds()).isPositive();
        assertThat(service.findById(USER, w.adhocId())).isPresent();
    }

    @Test
    void listExcludesArchivedRestoreBringsBack() {
        AdHocWorkout w = createBasic();
        assertThat(service.list(USER)).hasSize(1);

        service.archive(USER, w.adhocId());
        assertThat(service.list(USER)).isEmpty();
        assertThat(service.findById(USER, w.adhocId())).isEmpty();
        assertThat(service.listIncludingArchived(USER)).hasSize(1);

        service.restore(USER, w.adhocId());
        assertThat(service.list(USER)).hasSize(1);
        assertThat(service.findById(USER, w.adhocId())).isPresent();
    }

    @Test
    void updateChangesFieldsAndRecomputesEstimate() {
        AdHocWorkout w = createBasic();
        int before = w.estimatedDurationSeconds();

        // Add a second exercise → estimate must grow.
        Prescription a = new Prescription("ex_bench", 0, 3, 8, 12, null, null, 90, null, null, null, null);
        Prescription b = new Prescription("ex_row", 1, 3, 8, 12, null, null, 90, null, null, null, null);
        WorkoutDay bigger = new WorkoutDay(w.day().dayId(), "Full body", null, null, 0,
            List.of(new Block(null, BlockType.MAIN, "Main", 0, List.of(a, b))));

        AdHocWorkout updated = service.update(USER, w.adhocId(), "Renamed", null,
            List.of("Travel", "Full-body"), true, bigger, null, null);
        assertThat(updated.title()).isEqualTo("Renamed");
        assertThat(updated.pinned()).isTrue();
        assertThat(updated.tags()).containsExactly("Travel", "Full-body");
        assertThat(updated.estimatedDurationSeconds()).isGreaterThan(before);
    }

    @Test
    void updatePreservesRunCounters() {
        AdHocWorkout w = createBasic();
        // Simulate a prior run counter by saving directly.
        repo.save(new AdHocWorkout(USER, w.adhocId(), w.title(), w.summary(), w.source(), w.prompt(),
            w.equipmentContext(), w.targetDurationMinutes(), w.estimatedDurationSeconds(), w.tags(),
            w.pinned(), w.day(), 5, java.time.Instant.now(), w.createdAt(), w.updatedAt()));

        AdHocWorkout updated = service.update(USER, w.adhocId(), "New title", null, null, null, null, null, null);
        assertThat(updated.runCount()).isEqualTo(5);
        assertThat(updated.lastPerformedAt()).isNotNull();
    }
}
