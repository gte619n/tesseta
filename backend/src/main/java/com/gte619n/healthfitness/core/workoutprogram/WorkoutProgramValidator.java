package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseAvailabilityService;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.workoutprogram.TrainingScienceScaffold.Landmark;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Validates a proposed/edited program before it is persisted.
 *
 * <p>Two severities (IMPL-18 R1): {@link #validate} returns hard ERRORS that
 * block a commit — every prescription's exercise must exist, be PUBLISHED, be
 * executable at its day's gym, and no prescribed {@code targetWeightLbs} may
 * exceed the user's estimated 1RM. {@link #warnings} returns soft, override-able
 * advisories — weekly volume past MRV, a long phase with no deload, and a
 * faster-than-recommended phase-to-phase ramp — surfaced inline but not blocking.
 * Both return flat human-readable lists so the caller flags them rather than
 * silently dropping fields.
 */
@Service
public class WorkoutProgramValidator {

    /** A little slack so floating-point e1RM doesn't reject a legitimate top set. */
    private static final double E1RM_SLACK = 1.05;

    private final ExerciseRepository exercises;
    private final ExerciseAvailabilityService availability;
    private final TrainingScienceScaffold science;
    private final ExercisePerformanceDigestService digests;

    public WorkoutProgramValidator(ExerciseRepository exercises, ExerciseAvailabilityService availability,
                                   TrainingScienceScaffold science, ExercisePerformanceDigestService digests) {
        this.exercises = exercises;
        this.availability = availability;
        this.science = science;
        this.digests = digests;
    }

    public List<String> validate(String userId, WorkoutProgram program) {
        List<String> issues = new ArrayList<>();
        if (program.phases() == null || program.phases().isEmpty()) {
            issues.add("Program has no phases.");
            return issues;
        }
        // A program whose phases carry no training days materializes zero
        // sessions on activation — a silent empty "This week". Block it here so
        // the activate/commit 422 path explains why (IMPL-STAB G2). Each phase's
        // days are its weekly microcycle; if none has any, nothing is schedulable.
        boolean anySchedulableDay = program.phases().stream()
            .anyMatch(phase -> phase.days() != null && !phase.days().isEmpty());
        if (!anySchedulableDay) {
            issues.add("Program has no training days to schedule — add at least one workout day before activating.");
            return issues;
        }
        Map<String, ExerciseDigest> digestByExercise = safeDigest(userId);
        for (ProgramPhase phase : program.phases()) {
            if (phase.deloadWeekIndex() != null
                && (phase.deloadWeekIndex() < 1 || phase.deloadWeekIndex() > Math.max(1, phase.weeks()))) {
                issues.add("Phase '" + phase.title() + "': deload week " + phase.deloadWeekIndex()
                    + " is outside 1.." + phase.weeks() + ".");
            }
            for (WorkoutDay day : phase.days()) {
                validateDay(userId, phase, day, digestByExercise, issues);
            }
        }
        return issues;
    }

    private void validateDay(String userId, ProgramPhase phase, WorkoutDay day,
                             Map<String, ExerciseDigest> digestByExercise, List<String> issues) {
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
                // IMPL-18 R1: a prescribed weight above the user's e1RM is impossible — block it.
                if (rx.targetWeightLbs() != null) {
                    ExerciseDigest d = digestByExercise.get(rx.exerciseId());
                    if (d != null && d.estimated1Rm() != null
                        && rx.targetWeightLbs() > d.estimated1Rm() * E1RM_SLACK) {
                        issues.add(where + ": prescribed weight " + fmt(rx.targetWeightLbs()) + " lb for '"
                            + ex.name() + "' exceeds your estimated 1RM (" + fmt(d.estimated1Rm())
                            + " lb). Lower the load or prescribe by RPE/%1RM.");
                    }
                }
            }
        }
    }

    /**
     * Soft, override-able advisories (IMPL-18 R1): weekly volume past MRV, a long
     * phase missing a deload, and a faster-than-recommended phase-to-phase volume
     * ramp. Computed from {@code Exercise.primaryMuscles} against the science
     * scaffold. A phase's {@code days} is the weekly microcycle, so summing its
     * prescriptions' sets gives weekly hard sets per muscle.
     */
    public List<String> warnings(String userId, WorkoutProgram program) {
        List<String> warnings = new ArrayList<>();
        if (program.phases() == null || program.phases().isEmpty()) return warnings;
        Map<String, List<String>> musclesByExercise = primaryMusclesFor(program);

        Map<String, Integer> prevTotals = null;
        for (ProgramPhase phase : program.phases()) {
            Map<String, Integer> setsByMuscle = weeklySetsByMuscle(phase, musclesByExercise);

            setsByMuscle.forEach((muscle, sets) -> {
                Landmark lm = science.landmarkFor(muscle);
                if (lm != null && sets > lm.mrv()) {
                    warnings.add("Phase '" + phase.title() + "': " + sets + " weekly sets for " + muscle
                        + " exceeds its MRV (" + lm.mrv() + ") — past what's typically recoverable.");
                }
            });

            if (phase.weeks() >= TrainingScienceScaffold.DELOAD_PHASE_WEEKS_THRESHOLD
                && phase.deloadWeekIndex() == null) {
                warnings.add("Phase '" + phase.title() + "' runs " + phase.weeks()
                    + " weeks with no deload — consider a deload week.");
            }

            int total = setsByMuscle.values().stream().mapToInt(Integer::intValue).sum();
            if (prevTotals != null) {
                int prev = prevTotals.values().stream().mapToInt(Integer::intValue).sum();
                if (prev > 0 && total > prev * (1 + TrainingScienceScaffold.MAX_WEEKLY_SET_INCREASE)) {
                    int pct = (int) Math.round((total - prev) * 100.0 / prev);
                    warnings.add("Phase '" + phase.title() + "' jumps weekly volume " + pct
                        + "% over the previous phase — steeper than the recommended ramp; ease it in.");
                }
            }
            prevTotals = setsByMuscle;
        }
        return warnings;
    }

    private Map<String, Integer> weeklySetsByMuscle(ProgramPhase phase, Map<String, List<String>> musclesByExercise) {
        Map<String, Integer> setsByMuscle = new LinkedHashMap<>();
        for (WorkoutDay day : phase.days()) {
            for (Block block : day.blocks()) {
                for (Prescription rx : block.prescriptions()) {
                    if (rx.sets() == null) continue;
                    for (String muscle : musclesByExercise.getOrDefault(rx.exerciseId(), List.of())) {
                        String key = science.normalize(muscle);
                        if (key == null) continue;
                        setsByMuscle.merge(key, rx.sets(), Integer::sum);
                    }
                }
            }
        }
        return setsByMuscle;
    }

    private Map<String, List<String>> primaryMusclesFor(WorkoutProgram program) {
        List<String> ids = new ArrayList<>();
        for (ProgramPhase phase : program.phases()) {
            for (WorkoutDay day : phase.days()) {
                for (Block block : day.blocks()) {
                    for (Prescription rx : block.prescriptions()) {
                        if (rx.exerciseId() != null) ids.add(rx.exerciseId());
                    }
                }
            }
        }
        Map<String, List<String>> map = new HashMap<>();
        if (ids.isEmpty()) return map;
        for (Exercise e : exercises.findByIds(ids)) {
            map.put(e.exerciseId(), e.primaryMuscles() == null ? List.of() : e.primaryMuscles());
        }
        return map;
    }

    private Map<String, ExerciseDigest> safeDigest(String userId) {
        try {
            return digests.digestAll(userId);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
