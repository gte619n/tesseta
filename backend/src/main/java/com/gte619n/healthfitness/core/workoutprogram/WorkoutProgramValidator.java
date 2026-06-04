package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Validates a proposed/edited program before it is persisted. The hard rule:
 * every prescription's exercise must exist, be PUBLISHED, and be executable at
 * its day's gym. Returns a flat list of human-readable issues (empty = valid)
 * so the caller can flag them inline rather than silently dropping fields.
 */
@Service
public class WorkoutProgramValidator {

    private final ExerciseRepository exercises;
    private final ExerciseAvailabilityService availability;

    public WorkoutProgramValidator(ExerciseRepository exercises, ExerciseAvailabilityService availability) {
        this.exercises = exercises;
        this.availability = availability;
    }

    public List<String> validate(String userId, WorkoutProgram program) {
        List<String> issues = new ArrayList<>();
        if (program.phases() == null || program.phases().isEmpty()) {
            issues.add("Program has no phases.");
            return issues;
        }
        for (ProgramPhase phase : program.phases()) {
            if (phase.deloadWeekIndex() != null
                && (phase.deloadWeekIndex() < 1 || phase.deloadWeekIndex() > Math.max(1, phase.weeks()))) {
                issues.add("Phase '" + phase.title() + "': deload week " + phase.deloadWeekIndex()
                    + " is outside 1.." + phase.weeks() + ".");
            }
            for (WorkoutDay day : phase.days()) {
                validateDay(userId, phase, day, issues);
            }
        }
        return issues;
    }

    private void validateDay(String userId, ProgramPhase phase, WorkoutDay day, List<String> issues) {
        if (day.locationId() == null || day.locationId().isBlank()) {
            issues.add("Day '" + day.label() + "' has no gym assigned.");
            return;
        }
        for (Block block : day.blocks()) {
            for (Prescription rx : block.prescriptions()) {
                Exercise ex = exercises.findById(rx.exerciseId()).orElse(null);
                String where = "Day '" + day.label() + "' / " + block.type() + " block";
                if (ex == null) {
                    issues.add(where + ": unknown exercise '" + rx.exerciseId() + "'.");
                    continue;
                }
                if (ex.status() != ExerciseStatus.PUBLISHED) {
                    issues.add(where + ": exercise '" + ex.name() + "' is not published.");
                }
                if (ex.suitableBlockTypes() != null && !ex.suitableBlockTypes().isEmpty()
                    && !ex.suitableBlockTypes().contains(block.type())) {
                    issues.add(where + ": '" + ex.name() + "' is not suitable for a " + block.type() + " block.");
                }
                if (!availability.isExecutableAt(rx.exerciseId(), userId, day.locationId())) {
                    issues.add(where + ": '" + ex.name() + "' can't be performed with the equipment at this gym.");
                }
                boolean timed = ex.isTimed();
                if (timed && rx.durationSeconds() == null) {
                    issues.add(where + ": timed exercise '" + ex.name() + "' needs a duration.");
                }
                if (!timed && rx.sets() == null && rx.repsMin() == null) {
                    issues.add(where + ": '" + ex.name() + "' needs sets/reps.");
                }
            }
        }
    }
}
