package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutPrescriptionCustomizationService.InvalidPrescriptionEditException;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutPrescriptionCustomizationService.PrescriptionEdit;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.InMemoryLocationRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryScheduledWorkoutRepository;
import com.gte619n.healthfitness.testsupport.workoutprogram.InMemoryWorkoutProgramRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the #4 in-workout swap / rep-set edit across both scopes: the
 * current-session snapshot always changes; {@code applyToProgram} pushes the
 * same slot to the template day and future PLANNED sessions of that day, while
 * past/COMPLETED sessions and other days are left alone.
 */
class WorkoutPrescriptionCustomizationServiceTest {

    private static final String USER = "u-cust";
    private static final String PROGRAM = "p1";
    private static final String GYM = "gym-1";
    // 2026-06-03 is a Wednesday.
    private static final LocalDate DATE = LocalDate.of(2026, 6, 3);

    private InMemoryScheduledWorkoutRepository scheduled;
    private InMemoryWorkoutProgramRepository programs;
    private InMemoryExerciseRepository exercises;
    private WorkoutProgramService programService;
    private WorkoutPrescriptionCustomizationService service;

    @BeforeEach
    void setUp() {
        scheduled = new InMemoryScheduledWorkoutRepository();
        programs = new InMemoryWorkoutProgramRepository();
        exercises = new InMemoryExerciseRepository();
        InMemoryLocationRepository locations = new InMemoryLocationRepository();
        programService = new WorkoutProgramService(programs);
        ExerciseAvailabilityService availability =
            new ExerciseAvailabilityService(exercises, locations, true);
        service = new WorkoutPrescriptionCustomizationService(
            scheduled, programs, programService, availability);

        // A gym with a couple of pieces of gear; a bar for squats, dumbbells for
        // the goblet swap. "machine-x" needs a leg-press machine the gym lacks.
        locations.save(new Location(USER, GYM, GYM, null, null, true, Map.of(), List.of(),
            List.of("barbell", "dumbbell"), Map.of(), false, true, Instant.now(), Instant.now()));
        exercises.save(exercise("sq", List.of(new EquipmentRequirement(List.of("barbell")))));
        exercises.save(exercise("bp", List.of(new EquipmentRequirement(List.of("barbell")))));
        exercises.save(exercise("goblet-squat", List.of(new EquipmentRequirement(List.of("dumbbell")))));
        exercises.save(exercise("machine-x", List.of(new EquipmentRequirement(List.of("leg-press-machine")))));

        seedProgramAndSessions();
    }

    // ---- swap ----

    @Test
    void sessionScopeSwapChangesOnlyThisSession() {
        ScheduledWorkout updated = service.apply(USER, PROGRAM, id(DATE), "b1", 0, false,
            new PrescriptionEdit("goblet-squat", null, null, null));

        assertEquals("goblet-squat", rx(updated, 0).exerciseId());
        // Future session of the same day is untouched.
        assertEquals("sq", rx(session(DATE.plusDays(7)), 0).exerciseId());
        // Template is untouched.
        assertEquals("sq", templateRx(0).exerciseId());
    }

    @Test
    void programScopeSwapPropagatesToTemplateAndFutureButNotPastOrOtherDays() {
        service.apply(USER, PROGRAM, id(DATE), "b1", 0, true,
            new PrescriptionEdit("goblet-squat", null, null, null));

        // Current + future PLANNED sessions of this day, and the template.
        assertEquals("goblet-squat", rx(session(DATE), 0).exerciseId());
        assertEquals("goblet-squat", rx(session(DATE.plusDays(7)), 0).exerciseId());
        assertEquals("goblet-squat", templateRx(0).exerciseId());
        // A COMPLETED past session keeps what it was performed with.
        assertEquals("sq", rx(session(DATE.minusDays(7)), 0).exerciseId());
        // Another day using the same exercise is NOT touched.
        assertEquals("sq", rx(otherDaySession(), 0).exerciseId());
    }

    @Test
    void swapOntoNonGymExerciseIsRejected() {
        assertThrows(InvalidPrescriptionEditException.class, () ->
            service.apply(USER, PROGRAM, id(DATE), "b1", 0, false,
                new PrescriptionEdit("machine-x", null, null, null)));
    }

    @Test
    void swapOnPlannedSessionDropsLoggedSetsButCompletedKeepsThem() {
        // A COMPLETED session with a logged set; swapping re-points the exercise
        // but must keep the recorded history.
        ScheduledWorkout completed = session(DATE.minusDays(7));
        assertEquals(ScheduledStatus.COMPLETED, completed.status());
        ScheduledWorkout afterSwap = service.apply(USER, PROGRAM, id(DATE.minusDays(7)), "b1", 0, false,
            new PrescriptionEdit("goblet-squat", null, null, null));
        assertEquals("goblet-squat", rx(afterSwap, 0).exerciseId());
        assertNotNull(rx(afterSwap, 0).loggedSets());
        assertEquals(1, rx(afterSwap, 0).loggedSets().size());
    }

    // ---- reps/sets ----

    @Test
    void repsAndSetsEditAppliesAtBothScopes() {
        service.apply(USER, PROGRAM, id(DATE), "b1", 1, true,
            new PrescriptionEdit(null, 5, 3, 6));

        Prescription current = rx(session(DATE), 1);
        assertEquals(5, current.sets());
        assertEquals(3, current.repsMin());
        assertEquals(6, current.repsMax());
        // Exercise unchanged when only reps/sets edited.
        assertEquals("bp", current.exerciseId());
        // Future + template picked it up too.
        assertEquals(5, rx(session(DATE.plusDays(7)), 1).sets());
        assertEquals(5, templateRx(1).sets());
    }

    @Test
    void invertedRepRangeIsRejected() {
        assertThrows(InvalidPrescriptionEditException.class, () ->
            service.apply(USER, PROGRAM, id(DATE), "b1", 0, false,
                new PrescriptionEdit(null, null, 10, 5)));
    }

    @Test
    void emptyEditIsRejected() {
        assertThrows(InvalidPrescriptionEditException.class, () ->
            service.apply(USER, PROGRAM, id(DATE), "b1", 0, false,
                new PrescriptionEdit(null, null, null, null)));
    }

    // ---- fixtures ----

    private void seedProgramAndSessions() {
        WorkoutDay d1Template = day("d1", "Lower", DayOfWeek.WED,
            List.of(rx("sq", 0), rx("bp", 1)));
        WorkoutDay d2Template = day("d2", "Upper", DayOfWeek.FRI,
            List.of(rx("sq", 0)));  // reuses "sq" — must stay untouched
        ProgramPhase phase = new ProgramPhase("ph1", "Base", null, 0,
            ProgramPhaseStatus.ACTIVE, 4, null, DATE, DATE.plusWeeks(4), null,
            List.of(d1Template, d2Template), null);
        programs.save(new WorkoutProgram(USER, PROGRAM, "P1", null, null,
            ProgramStatus.ACTIVE, ProgramSource.MANUAL, DATE, null,
            List.of("ph1"), List.of(phase), Instant.now(), Instant.now(), null, null));

        // Current PLANNED d1, a future PLANNED d1, a past COMPLETED d1, and a future d2.
        scheduled.save(planned(DATE, "d1", "Lower", d1Template));
        scheduled.save(planned(DATE.plusDays(7), "d1", "Lower", d1Template));
        scheduled.save(completedWithSet(DATE.minusDays(7), d1Template));
        scheduled.save(planned(DATE.plusDays(2), "d2", "Upper", d2Template));
    }

    private ScheduledWorkout planned(LocalDate date, String dayId, String label, WorkoutDay template) {
        WorkoutDay snapshot = new WorkoutDay(dayId, label, template.dayOfWeek(), GYM,
            template.orderIndex(), template.blocks());
        return new ScheduledWorkout(USER, PROGRAM, date + "_" + dayId, date, "ph1", dayId, label,
            1, false, GYM, ScheduledStatus.PLANNED, snapshot, null, null, null);
    }

    private ScheduledWorkout completedWithSet(LocalDate date, WorkoutDay template) {
        Prescription performed = new Prescription("sq", 0, 3, 5, 8, null, null, 120, null, null, null,
            List.of(new LoggedSet(225.0, 5, null, null, Instant.now())));
        WorkoutDay snapshot = new WorkoutDay("d1", "Lower", DayOfWeek.WED, GYM, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, List.of(performed, rx("bp", 1)))));
        return new ScheduledWorkout(USER, PROGRAM, date + "_d1", date, "ph1", "d1", "Lower",
            1, false, GYM, ScheduledStatus.COMPLETED, snapshot, Instant.now(), 3600, null);
    }

    private static WorkoutDay day(String dayId, String label, DayOfWeek dow, List<Prescription> rxs) {
        return new WorkoutDay(dayId, label, dow, GYM, 0,
            List.of(new Block("b1", BlockType.MAIN, "Main", 0, rxs)));
    }

    private static Prescription rx(String exerciseId, int orderIndex) {
        return new Prescription(exerciseId, orderIndex, 3, 5, 8, null, null, 120, null, null, null, null);
    }

    private static Exercise exercise(String id, List<EquipmentRequirement> reqs) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), reqs, List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }

    private static String id(LocalDate date) {
        return date + "_d1";
    }

    private ScheduledWorkout session(LocalDate date) {
        return scheduled.findById(USER, PROGRAM, date + "_d1").orElseThrow();
    }

    private ScheduledWorkout otherDaySession() {
        return scheduled.findById(USER, PROGRAM, DATE.plusDays(2) + "_d2").orElseThrow();
    }

    private static Prescription rx(ScheduledWorkout sw, int orderIndex) {
        return sw.session().blocks().get(0).prescriptions().get(orderIndex);
    }

    private Prescription templateRx(int orderIndex) {
        WorkoutProgram p = programs.findById(USER, PROGRAM).orElseThrow();
        return p.phases().get(0).days().get(0).blocks().get(0).prescriptions().get(orderIndex);
    }
}
