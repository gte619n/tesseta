package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.BlockResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.PhaseResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.PrescriptionResponse;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds deep + scheduled responses, resolving embedded exercise summaries
 * (one batch fetch), gym names, and the linked goal title — so clients render
 * sessions without N+1 lookups (see IMPL-15).
 */
@Component
public class WorkoutProgramAssembler {

    private final ExerciseService exercises;
    private final LocationRepository locations;
    private final GoalRepository goals;

    public WorkoutProgramAssembler(ExerciseService exercises, LocationRepository locations, GoalRepository goals) {
        this.exercises = exercises;
        this.locations = locations;
        this.goals = goals;
    }

    public WorkoutProgramDeepResponse deep(WorkoutProgram p) {
        return deep(p, List.of());
    }

    /**
     * Deep response, optionally backfilling each phase's day list from performed
     * sessions. A phase with no template days (e.g. the imported-history program,
     * whose source has no weekly microcycle) is rendered from its {@code sessions}
     * — each performed session becomes a day under its phase, ordered by date — so
     * the program view shows the workouts that happened rather than an empty phase.
     * Phases that carry a real template are left untouched.
     */
    public WorkoutProgramDeepResponse deep(WorkoutProgram p, List<ScheduledWorkout> sessions) {
        // Sessions grouped by phase, oldest first, so derived days read chronologically.
        Map<String, List<ScheduledWorkout>> sessionsByPhase = new HashMap<>();
        List<ScheduledWorkout> ordered = new ArrayList<>(sessions);
        ordered.sort(Comparator.comparing(
            ScheduledWorkout::date, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ScheduledWorkout sw : ordered) {
            if (sw.session() != null && sw.phaseId() != null) {
                sessionsByPhase.computeIfAbsent(sw.phaseId(), k -> new ArrayList<>()).add(sw);
            }
        }

        Set<String> exerciseIds = collectExerciseIds(p.phases());
        exerciseIds.addAll(collectExerciseIdsFromDays(sessionDays(ordered)));
        Map<String, ExerciseSummary> summaries = summariesFor(exerciseIds);
        Set<String> locationIds = collectLocationIds(p.phases());
        for (ScheduledWorkout sw : ordered) {
            if (sw.locationId() != null) locationIds.add(sw.locationId());
        }
        Map<String, String> gymNames = gymNamesFor(p.userId(), locationIds);
        String goalTitle = p.goalId() == null ? null
            : goals.findById(p.userId(), p.goalId()).map(g -> g.title()).orElse(null);

        List<PhaseResponse> phases = new ArrayList<>();
        for (ProgramPhase ph : p.phases()) {
            List<DayResponse> days = new ArrayList<>();
            if (!ph.days().isEmpty()) {
                for (WorkoutDay d : ph.days()) {
                    days.add(dayResponse(d, summaries, gymNames));
                }
            } else {
                for (ScheduledWorkout sw : sessionsByPhase.getOrDefault(ph.phaseId(), List.of())) {
                    days.add(dayResponse(sw.session(), summaries, gymNames));
                }
            }
            phases.add(new PhaseResponse(
                ph.phaseId(), ph.title(), ph.focus(), ph.orderIndex(), ph.status(),
                ph.weeks(), ph.deloadWeekIndex(), ph.targetStartDate(), ph.targetEndDate(), days));
        }
        return new WorkoutProgramDeepResponse(
            p.programId(), p.title(), p.description(), p.goalId(), goalTitle, p.status(), p.source(),
            p.startDate(),
            p.schedule() == null ? List.of() : p.schedule().trainingDays(),
            phases, p.createdAt(), p.updatedAt(), p.completedAt());
    }

    private static List<WorkoutDay> sessionDays(List<ScheduledWorkout> sessions) {
        List<WorkoutDay> days = new ArrayList<>();
        for (ScheduledWorkout sw : sessions) {
            if (sw.session() != null) days.add(sw.session());
        }
        return days;
    }

    public List<ScheduledWorkoutResponse> scheduled(String userId, List<ScheduledWorkout> items) {
        List<WorkoutDay> sessions = items.stream().map(ScheduledWorkout::session)
            .filter(d -> d != null).toList();
        Map<String, ExerciseSummary> summaries = summariesFor(collectExerciseIdsFromDays(sessions));
        Set<String> locIds = new HashSet<>();
        items.forEach(i -> { if (i.locationId() != null) locIds.add(i.locationId()); });
        Map<String, String> gymNames = gymNamesFor(userId, locIds);

        List<ScheduledWorkoutResponse> out = new ArrayList<>();
        for (ScheduledWorkout sw : items) {
            DayResponse session = sw.session() == null ? null : dayResponse(sw.session(), summaries, gymNames);
            out.add(new ScheduledWorkoutResponse(
                sw.programId(), sw.scheduledId(), sw.date(), sw.phaseId(), sw.dayId(), sw.dayLabel(),
                sw.weekIndexInPhase(), sw.isDeload(), sw.locationId(),
                gymNames.get(sw.locationId()), sw.status(), session,
                sw.completedAt(), sw.durationSeconds()));
        }
        return out;
    }

    private DayResponse dayResponse(WorkoutDay d, Map<String, ExerciseSummary> summaries, Map<String, String> gymNames) {
        List<BlockResponse> blocks = new ArrayList<>();
        for (Block b : d.blocks()) {
            List<PrescriptionResponse> rxs = new ArrayList<>();
            for (Prescription rx : b.prescriptions()) {
                rxs.add(new PrescriptionResponse(
                    rx.exerciseId(), rx.orderIndex(), rx.sets(), rx.repsMin(), rx.repsMax(),
                    rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(), rx.notes(),
                    rx.deloadModifier(), rx.loggedSets(), summaries.get(rx.exerciseId())));
            }
            blocks.add(new BlockResponse(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return new DayResponse(d.dayId(), d.label(), d.dayOfWeek(), d.locationId(),
            gymNames.get(d.locationId()), d.orderIndex(), blocks);
    }

    private Map<String, ExerciseSummary> summariesFor(Set<String> ids) {
        Map<String, ExerciseSummary> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Exercise e : exercises.findByIds(new ArrayList<>(ids))) {
            map.put(e.exerciseId(), ExerciseSummary.from(e));
        }
        return map;
    }

    private Map<String, String> gymNamesFor(String userId, Set<String> locationIds) {
        Map<String, String> map = new HashMap<>();
        if (locationIds.isEmpty()) {
            return map;
        }
        // One read of the user's locations rather than a findById per gym.
        for (Location l : locations.findByUser(userId, true)) {
            if (locationIds.contains(l.locationId())) {
                map.put(l.locationId(), l.name());
            }
        }
        return map;
    }

    private static Set<String> collectExerciseIds(List<ProgramPhase> phases) {
        Set<String> ids = new HashSet<>();
        if (phases == null) return ids;
        for (ProgramPhase ph : phases) {
            ids.addAll(collectExerciseIdsFromDays(ph.days()));
        }
        return ids;
    }

    private static Set<String> collectExerciseIdsFromDays(List<WorkoutDay> days) {
        Set<String> ids = new HashSet<>();
        if (days == null) return ids;
        for (WorkoutDay d : days) {
            if (d.blocks() == null) continue;
            for (Block b : d.blocks()) {
                if (b.prescriptions() == null) continue;
                for (Prescription rx : b.prescriptions()) {
                    if (rx.exerciseId() != null) ids.add(rx.exerciseId());
                }
            }
        }
        return ids;
    }

    private static Set<String> collectLocationIds(List<ProgramPhase> phases) {
        Set<String> ids = new HashSet<>();
        if (phases == null) return ids;
        for (ProgramPhase ph : phases) {
            for (WorkoutDay d : ph.days()) {
                if (d.locationId() != null) ids.add(d.locationId());
            }
        }
        return ids;
    }
}
