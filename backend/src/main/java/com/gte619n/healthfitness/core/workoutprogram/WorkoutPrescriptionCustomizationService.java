package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * In-workout exercise swap / rep-set edit that can be applied to just the
 * current session or pushed across the program (#4 "apply to program").
 *
 * <p>The current session's denormalized {@link WorkoutDay} snapshot is always
 * updated. When {@code applyToProgram} is set, the same edit is written to the
 * program <em>template</em> (this day's block/order slot) and to every FUTURE
 * PLANNED session of that day (date strictly after the current session) — so
 * next week's squat becomes the swapped movement too, while past/COMPLETED
 * sessions and other days keep what they had.
 *
 * <p>Mirrors the Android live-draft behaviour: swapping an exercise on a
 * still-PLANNED slot starts it fresh (drops logged sets and the
 * history-grounded load), but a swap on a COMPLETED session only re-points the
 * exercise — its recorded {@code loggedSets} are history, not to be wiped.
 */
@Service
public class WorkoutPrescriptionCustomizationService {

    private final ScheduledWorkoutRepository scheduled;
    private final WorkoutProgramRepository programs;
    private final WorkoutProgramService programService;
    private final ExerciseAvailabilityService availability;

    public WorkoutPrescriptionCustomizationService(
        ScheduledWorkoutRepository scheduled,
        WorkoutProgramRepository programs,
        WorkoutProgramService programService,
        ExerciseAvailabilityService availability
    ) {
        this.scheduled = scheduled;
        this.programs = programs;
        this.programService = programService;
        this.availability = availability;
    }

    /** The fields a customization can change; any left null is untouched. */
    public record PrescriptionEdit(
        String exerciseId, Integer sets, Integer repsMin, Integer repsMax
    ) {
        boolean isSwap(Prescription rx) {
            return exerciseId != null && !exerciseId.isBlank() && !exerciseId.equals(rx.exerciseId());
        }

        boolean isEmpty() {
            return (exerciseId == null || exerciseId.isBlank())
                && sets == null && repsMin == null && repsMax == null;
        }
    }

    /** A customization that fails validation (bad rep range, unknown slot, non-gym exercise). */
    public static class InvalidPrescriptionEditException extends RuntimeException {
        public InvalidPrescriptionEditException(String message) {
            super(message);
        }
    }

    /**
     * Apply {@code edit} to the {@code (blockId, orderIndex)} slot of the given
     * session, returning the updated session. The session is expected to already
     * exist (the controller materializes an ad-hoc one first); a miss is an
     * {@link IllegalArgumentException} (→ 404 at the edge).
     */
    public ScheduledWorkout apply(
        String userId, String programId, String scheduledId,
        String blockId, int orderIndex, boolean applyToProgram, PrescriptionEdit edit
    ) {
        if (edit == null || edit.isEmpty()) {
            throw new InvalidPrescriptionEditException("No prescription changes were provided.");
        }
        ScheduledWorkout session = scheduled.findById(userId, programId, scheduledId)
            .orElseThrow(() -> new IllegalArgumentException("Scheduled session not found: " + scheduledId));

        // Validate a swap target is actually doable at this session's gym.
        if (edit.exerciseId() != null && !edit.exerciseId().isBlank()
            && !availability.isExecutableAt(edit.exerciseId(), userId, session.locationId())) {
            throw new InvalidPrescriptionEditException(
                "That exercise can't be performed with this workout's gym equipment.");
        }

        boolean sessionPlanned = session.status() == ScheduledStatus.PLANNED;
        WorkoutDay updatedDay = editDay(session.session(), blockId, orderIndex, edit, sessionPlanned,
            /* required */ true);
        ScheduledWorkout updated = withSession(session, updatedDay);
        scheduled.saveSessions(List.of(updated));

        if (applyToProgram) {
            propagateToProgram(userId, programId, session, blockId, orderIndex, edit);
        }
        return updated;
    }

    /**
     * Push the same slot edit to the program template's matching day and to
     * every future PLANNED session of that day. Future sessions and the template
     * are always PLANNED-equivalent, so a swap resets their logged sets/load.
     */
    private void propagateToProgram(
        String userId, String programId, ScheduledWorkout session,
        String blockId, int orderIndex, PrescriptionEdit edit
    ) {
        // 1) Template: the phase's day of this session's dayId.
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
        List<ProgramPhase> newPhases = new ArrayList<>();
        boolean touched = false;
        for (ProgramPhase phase : program.phases()) {
            if (!phase.phaseId().equals(session.phaseId()) || phase.days() == null) {
                newPhases.add(phase);
                continue;
            }
            List<WorkoutDay> newDays = new ArrayList<>();
            for (WorkoutDay day : phase.days()) {
                if (day.dayId().equals(session.dayId())) {
                    newDays.add(editDay(day, blockId, orderIndex, edit, /* planned */ true, /* required */ false));
                    touched = true;
                } else {
                    newDays.add(day);
                }
            }
            newPhases.add(new ProgramPhase(
                phase.phaseId(), phase.title(), phase.focus(), phase.orderIndex(), phase.status(),
                phase.weeks(), phase.deloadWeekIndex(), phase.targetStartDate(), phase.targetEndDate(),
                phase.completedAt(), newDays, phase.nutritionGuidance()));
        }
        if (touched) {
            // Reuse the sanctioned template-mutation path (re-normalizes ids/order).
            programService.update(userId, programId, null, null, null, null, null, null, newPhases);
        }

        // 2) Future PLANNED sessions of this day, strictly after the current one.
        List<ScheduledWorkout> future = scheduled.findByProgram(
            userId, programId, session.date().plusDays(1), LocalDate.MAX);
        List<ScheduledWorkout> edited = new ArrayList<>();
        for (ScheduledWorkout sw : future) {
            if (sw.status() != ScheduledStatus.PLANNED || !session.dayId().equals(sw.dayId())) {
                continue;
            }
            WorkoutDay day = editDay(sw.session(), blockId, orderIndex, edit, /* planned */ true, /* required */ false);
            edited.add(withSession(sw, day));
        }
        if (!edited.isEmpty()) {
            scheduled.saveSessions(edited);
        }
    }

    /**
     * Rebuild {@code day} with {@code edit} applied to the prescription at
     * {@code (blockId, orderIndex)}. When {@code required} is true (the current
     * session) a missing slot is an error; when false (template / future
     * sessions that might have diverged) the day is returned unchanged.
     */
    private WorkoutDay editDay(
        WorkoutDay day, String blockId, int orderIndex, PrescriptionEdit edit,
        boolean planned, boolean required
    ) {
        if (day == null || day.blocks() == null) {
            if (required) {
                throw new IllegalArgumentException("Session has no blocks to customize.");
            }
            return day;
        }
        boolean found = false;
        List<Block> newBlocks = new ArrayList<>();
        for (Block block : day.blocks()) {
            if (!block.blockId().equals(blockId) || block.prescriptions() == null) {
                newBlocks.add(block);
                continue;
            }
            List<Prescription> newRxs = new ArrayList<>();
            for (Prescription rx : block.prescriptions()) {
                if (rx.orderIndex() == orderIndex) {
                    newRxs.add(applyEdit(rx, edit, planned));
                    found = true;
                } else {
                    newRxs.add(rx);
                }
            }
            newBlocks.add(new Block(block.blockId(), block.type(), block.title(), block.orderIndex(), newRxs));
        }
        if (!found && required) {
            throw new IllegalArgumentException(
                "No prescription at block " + blockId + " / order " + orderIndex + ".");
        }
        return new WorkoutDay(day.dayId(), day.label(), day.dayOfWeek(), day.locationId(),
            day.orderIndex(), newBlocks);
    }

    /** Merge non-null edit fields onto a prescription; a swap starts a PLANNED slot fresh. */
    private Prescription applyEdit(Prescription rx, PrescriptionEdit edit, boolean planned) {
        boolean swapped = edit.isSwap(rx);
        String exerciseId = swapped ? edit.exerciseId() : rx.exerciseId();
        Integer sets = edit.sets() != null ? edit.sets() : rx.sets();
        Integer repsMin = edit.repsMin() != null ? edit.repsMin() : rx.repsMin();
        Integer repsMax = edit.repsMax() != null ? edit.repsMax() : rx.repsMax();
        validateTargets(sets, repsMin, repsMax);

        // A swap on a still-PLANNED slot drops the old movement's logged sets and
        // history-grounded load; a swap on a COMPLETED session keeps its recorded
        // sets (that's history) and only re-points the exercise.
        boolean resetActuals = swapped && planned;
        return new Prescription(
            exerciseId, rx.orderIndex(), sets, repsMin, repsMax,
            rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(), rx.notes(),
            rx.deloadModifier(),
            resetActuals ? null : rx.loggedSets(),
            resetActuals ? null : rx.targetWeightLbs(),
            resetActuals ? null : rx.loadBasis());
    }

    private void validateTargets(Integer sets, Integer repsMin, Integer repsMax) {
        if (sets != null && sets < 1) {
            throw new InvalidPrescriptionEditException("Sets must be at least 1.");
        }
        if (repsMin != null && repsMin < 1) {
            throw new InvalidPrescriptionEditException("Reps must be at least 1.");
        }
        if (repsMax != null && repsMax < 1) {
            throw new InvalidPrescriptionEditException("Reps must be at least 1.");
        }
        if (repsMin != null && repsMax != null && repsMin > repsMax) {
            throw new InvalidPrescriptionEditException("The minimum reps can't exceed the maximum.");
        }
    }

    private static ScheduledWorkout withSession(ScheduledWorkout sw, WorkoutDay session) {
        return new ScheduledWorkout(
            sw.userId(), sw.programId(), sw.scheduledId(), sw.date(), sw.phaseId(), sw.dayId(),
            sw.dayLabel(), sw.weekIndexInPhase(), sw.isDeload(), sw.locationId(), sw.status(),
            session, sw.completedAt(), sw.durationSeconds(), sw.feeling());
    }
}
