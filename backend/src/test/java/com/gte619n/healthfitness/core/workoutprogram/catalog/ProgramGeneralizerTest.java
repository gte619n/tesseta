package com.gte619n.healthfitness.core.workoutprogram.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.IntensityKind;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P3.2 (D10) — full generalization: loads→%, strip notes/ids. */
class ProgramGeneralizerTest {

    private static Prescription absoluteLoad(int reps, double weightLbs) {
        // pre-IMPL-18 ctor + the IMPL-18 load ctor to attach an absolute weight,
        // plus notes & a logged set to prove they are stripped.
        return new Prescription(
            "ex-squat", 0, 3, reps, reps, null,
            new Intensity(IntensityKind.NONE, null), 120, "3-0-1",
            "author's personal note", null,
            List.of(new LoggedSet(weightLbs, reps, 8.0, 120, Instant.now(), null)),
            weightLbs, "last done");
    }

    private static ProgramPhase phase(Prescription... rxs) {
        WorkoutDay day = new WorkoutDay("d1", "Day 1", null, "gym-123", 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(rxs))));
        return new ProgramPhase("ph1", "Base", "Hypertrophy", 0,
            ProgramPhaseStatus.ACTIVE, 4, null,
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 28), Instant.now(),
            List.of(day));
    }

    @Test
    void absoluteLoadBecomesPercent1RmAndStripsNotesAndLoggedSets() {
        List<ProgramPhase> out = ProgramGeneralizer.generalizePhases(List.of(phase(absoluteLoad(10, 185))));
        Prescription rx = out.get(0).days().get(0).blocks().get(0).prescriptions().get(0);

        // absolute weight gone, replaced by a relative %1RM
        assertThat(rx.targetWeightLbs()).isNull();
        assertThat(rx.loadBasis()).isNull();
        assertThat(rx.intensity().kind()).isEqualTo(IntensityKind.PERCENT_1RM);
        // 10 reps → ~75% (Epley inverse, rounded)
        assertThat(rx.intensity().value()).isCloseTo(75.0, within(1.0));

        // per-user coaching stripped
        assertThat(rx.notes()).isNull();
        assertThat(rx.loggedSets()).isNull();
        // but the training prescription (sets/reps/rest/tempo) is preserved
        assertThat(rx.sets()).isEqualTo(3);
        assertThat(rx.repsMax()).isEqualTo(10);
        assertThat(rx.restSeconds()).isEqualTo(120);
    }

    @Test
    void existingRpeIntensityIsPreserved() {
        Prescription rpe = new Prescription(
            "ex-bench", 0, 4, 5, 5, null,
            new Intensity(IntensityKind.RPE, 8.0), 180, null, "note", null, null);
        List<ProgramPhase> out = ProgramGeneralizer.generalizePhases(List.of(phase(rpe)));
        Prescription rx = out.get(0).days().get(0).blocks().get(0).prescriptions().get(0);
        assertThat(rx.intensity().kind()).isEqualTo(IntensityKind.RPE);
        assertThat(rx.intensity().value()).isEqualTo(8.0);
        assertThat(rx.notes()).isNull();
    }

    @Test
    void perPhaseSchedulingAndGymAreStripped() {
        List<ProgramPhase> out = ProgramGeneralizer.generalizePhases(List.of(phase(absoluteLoad(5, 225))));
        ProgramPhase p = out.get(0);
        assertThat(p.targetStartDate()).isNull();
        assertThat(p.targetEndDate()).isNull();
        assertThat(p.completedAt()).isNull();
        assertThat(p.days().get(0).locationId()).isNull();
    }

    @Test
    void percentOf1RmForRepsFollowsEpleyInverse() {
        assertThat(ProgramGeneralizer.percentOf1RmForReps(1)).isCloseTo(97.0, within(1.0));
        assertThat(ProgramGeneralizer.percentOf1RmForReps(5)).isCloseTo(86.0, within(1.0));
        assertThat(ProgramGeneralizer.percentOf1RmForReps(10)).isCloseTo(75.0, within(1.0));
        assertThat(ProgramGeneralizer.percentOf1RmForReps(12)).isCloseTo(71.0, within(1.0));
    }
}
