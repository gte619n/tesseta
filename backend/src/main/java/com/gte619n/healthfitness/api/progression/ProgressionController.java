package com.gte619n.healthfitness.api.progression;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.progression.BlockLoop;
import com.gte619n.healthfitness.core.progression.BlockMode;
import com.gte619n.healthfitness.core.progression.BlockParameters;
import com.gte619n.healthfitness.core.progression.LoadConventionResolver;
import com.gte619n.healthfitness.core.progression.ProgressionEngine;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import com.gte619n.healthfitness.core.progression.SuccessCriterion;
import com.gte619n.healthfitness.core.progression.WeekLoop;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read/override surface for the progression engine (IMPL-PROG-01 §12). Uses the
 * app's own {@code /api/me/...} convention (decision A1 in the log) rather than
 * the spec's {@code /api/v1/...}, which is the third-party read API. Every
 * response carries the reasoning so the user can see why a number changed.
 */
@RestController
@RequestMapping("/api/me/progression")
public class ProgressionController {

    private final CurrentUserProvider currentUser;
    private final ProgressionEngine engine;
    private final ProgressionStateRepository states;
    private final ExerciseRepository exercises;
    private final LoadConventionResolver conventions;
    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;

    public ProgressionController(
        CurrentUserProvider currentUser, ProgressionEngine engine,
        ProgressionStateRepository states, ExerciseRepository exercises,
        LoadConventionResolver conventions,
        WorkoutProgramRepository programs, ScheduledWorkoutRepository scheduled) {
        this.currentUser = currentUser;
        this.engine = engine;
        this.states = states;
        this.exercises = exercises;
        this.conventions = conventions;
        this.programs = programs;
        this.scheduled = scheduled;
    }

    /** e1rm / sigma / confidence / trend for one exercise. */
    @GetMapping("/state/{exerciseId}")
    public ResponseEntity<StateDto> state(@PathVariable String exerciseId) {
        String userId = currentUser.get().userId();
        return engine.state(userId, exerciseId)
            .map(s -> ResponseEntity.ok(StateDto.of(s)))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Manual override: forks state to cold, retaining observation history. */
    @PostMapping("/state/{exerciseId}/reset")
    public ResponseEntity<Void> reset(@PathVariable String exerciseId) {
        engine.resetState(currentUser.get().userId(), exerciseId);
        return ResponseEntity.noContent().build();
    }

    /** Trend + fatigue + proposed volume/deload per movement pattern, with reasoning. */
    @GetMapping("/week-review")
    public List<WeekReviewDto> weekReview() {
        String userId = currentUser.get().userId();
        return engine.weekReview(userId).stream().map(WeekReviewDto::of).toList();
    }

    @GetMapping("/block-parameters")
    public BlockParametersDto blockParameters() {
        return BlockParametersDto.of(engine.blockParameters(currentUser.get().userId()));
    }

    /** Measured energy state (maintenance TDEE, mean intake, balance) — powers the plan view. */
    @GetMapping("/energy-balance")
    public EnergyBalanceDto energyBalance() {
        return EnergyBalanceDto.of(engine.energyBalance(currentUser.get().userId()));
    }

    /**
     * Per-exercise estimated 1RM (the "weights") — every tracked lift with a
     * belief, named and sorted heaviest-first. e1rm is the only authoritative
     * number in the engine; this is the read view of it.
     */
    @GetMapping("/strength")
    public List<ExerciseStrengthDto> strength() {
        String userId = currentUser.get().userId();
        List<ProgressionState> all = states.findAll(userId);
        Map<String, Exercise> byId = exercises
            .findByIds(all.stream().map(ProgressionState::exerciseId).toList())
            .stream()
            .collect(Collectors.toMap(Exercise::exerciseId, e -> e));
        Map<String, Integer> factors = conventions.factors(
            userId, all.stream().map(ProgressionState::exerciseId).toList());
        return all.stream()
            .filter(s -> s.e1rmLbs() > 0) // skip cold states with no belief yet
            .sorted(Comparator.comparingDouble(ProgressionState::e1rmLbs).reversed())
            .map(s -> {
                Exercise ex = byId.get(s.exerciseId());
                int factor = factors.getOrDefault(s.exerciseId(), 1);
                return new ExerciseStrengthDto(
                    s.exerciseId(),
                    ex != null ? ex.name() : s.exerciseId(),
                    ex != null ? ex.movementPattern().name() : null,
                    s.e1rmLbs(), s.confidence().name(), s.observationCount(),
                    factor, s.e1rmLbs() * factor);
            })
            .toList();
    }

    /** How many log rows the audit returns (newest first). */
    private static final int LOG_LIMIT = 100;

    /**
     * IMPL-DELOAD-01 (D4/DD-7): the per-lift progression audit — one row per
     * completed session the exercise appeared in, newest first, carrying the
     * engine's target/basis/rationale (retained at completion since P0; null for
     * pre-retention history) beside the top performed set. Derived on read from
     * the completed sessions; nothing new is stored.
     */
    @GetMapping("/log")
    public ProgressionLogDto log(@RequestParam String exerciseId) {
        String userId = currentUser.get().userId();
        Exercise ex = exercises.findById(exerciseId).orElse(null);
        int loadFactor = conventions.factor(userId, exerciseId);

        List<ProgressionLogRowDto> rows = new ArrayList<>();
        for (WorkoutProgram program : programs.findByUserIncludingArchived(userId)) {
            if (program == null || program.programId() == null) continue;
            for (ScheduledWorkout sw : scheduled.findByProgram(
                    userId, program.programId(), LocalDate.MIN, LocalDate.MAX)) {
                if (sw == null || sw.status() != ScheduledStatus.COMPLETED
                    || sw.session() == null || sw.session().blocks() == null || sw.date() == null) {
                    continue;
                }
                for (Block b : sw.session().blocks()) {
                    if (b == null || b.prescriptions() == null) continue;
                    for (Prescription rx : b.prescriptions()) {
                        if (rx == null || !exerciseId.equals(rx.exerciseId())
                            || rx.durationSeconds() != null) {
                            continue;
                        }
                        rows.add(logRow(sw, program.programId(), rx, loadFactor));
                    }
                }
            }
        }
        rows.sort(Comparator.comparing(ProgressionLogRowDto::date).reversed());
        if (rows.size() > LOG_LIMIT) rows = new ArrayList<>(rows.subList(0, LOG_LIMIT));
        return new ProgressionLogDto(
            exerciseId, ex != null ? ex.name() : exerciseId, loadFactor, rows);
    }

    private static ProgressionLogRowDto logRow(
        ScheduledWorkout sw, String programId, Prescription rx, int loadFactor) {
        Double topWeight = null;
        Integer topReps = null;
        int loggedCount = 0;
        if (rx.loggedSets() != null) {
            for (LoggedSet s : rx.loggedSets()) {
                if (s == null) continue;
                loggedCount++;
                if (s.weightLbs() != null && s.reps() != null
                    && (topWeight == null || s.weightLbs() > topWeight)) {
                    topWeight = s.weightLbs();
                    topReps = s.reps();
                }
            }
        }
        var rationale = rx.rationale();
        return new ProgressionLogRowDto(
            sw.date().toString(),
            rationale != null && rationale.path() != null ? rationale.path().name() : null,
            rationale != null && rationale.direction() != null ? rationale.direction().name() : null,
            rx.targetWeightLbs(), rx.loadBasis(),
            rationale != null && rationale.inputs() != null ? rationale.inputs() : List.of(),
            topWeight, topReps, loggedCount, sw.isDeload(),
            programId, sw.scheduledId(),
            rx.targetWeightLbs() == null ? null : rx.targetWeightLbs() * loadFactor,
            topWeight == null ? null : topWeight * loadFactor);
    }

    /** Manual mode override (D12) — pins params so the block loop won't overwrite. */
    @PutMapping("/block-parameters")
    public BlockParametersDto overrideBlockParameters(@RequestBody BlockOverrideDto body) {
        String userId = currentUser.get().userId();
        BlockParameters current = engine.blockParameters(userId);
        BlockMode mode = body.mode() != null ? BlockMode.valueOf(body.mode()) : current.mode();
        SuccessCriterion criterion = body.successCriterion() != null
            ? SuccessCriterion.valueOf(body.successCriterion()) : current.successCriterion();
        BlockParameters override = new BlockParameters(
            userId, mode,
            body.expectedDriftPerDay() != null ? body.expectedDriftPerDay() : current.expectedDriftPerDay(),
            current.repRangesByPattern(), current.rirCapsByMechanic(), current.weeklySetCeiling(),
            criterion, true, current.computedAt(), current.version());
        return BlockParametersDto.of(engine.overrideBlockParameters(userId, override));
    }

    // ---- DTOs ----

    public record StateDto(
        String exerciseId, double e1rmLbs, double sigmaLbs, String confidence,
        int observationCount, String lastObservedAt, boolean kalmanLive) {
        static StateDto of(ProgressionState s) {
            boolean live = s.kalmanEligibleAt() != null && !s.kalmanEligibleAt().isAfter(java.time.Instant.now());
            return new StateDto(s.exerciseId(), s.e1rmLbs(), s.sigmaLbs(), s.confidence().name(),
                s.observationCount(), s.lastObservedAt() == null ? null : s.lastObservedAt().toString(), live);
        }
    }

    public record WeekReviewDto(
        String pattern, String trend, double weeklySlopePct, double fatigueIndex,
        int currentTarget, int proposedTarget, boolean deload, String reasoning) {
        static WeekReviewDto of(WeekLoop.PatternReview r) {
            return new WeekReviewDto(r.pattern().name(), r.trend().name(), r.weeklySlopeFraction() * 100,
                r.fatigueIndex(), r.currentTarget(), r.proposedTarget(), r.deload(), r.reasoning());
        }
    }

    public record BlockParametersDto(
        String mode, double expectedDriftPerDay, String successCriterion, boolean manualOverride,
        Map<String, int[]> repRanges, Map<String, Double> rirCaps, Map<String, Integer> weeklyCeiling) {
        static BlockParametersDto of(BlockParameters p) {
            Map<String, int[]> reps = p.repRangesByPattern() == null ? Map.of()
                : p.repRangesByPattern().entrySet().stream().collect(Collectors.toMap(
                    e -> e.getKey().name(), e -> new int[]{e.getValue().min(), e.getValue().max()}));
            Map<String, Double> caps = p.rirCapsByMechanic() == null ? Map.of()
                : p.rirCapsByMechanic().entrySet().stream().collect(Collectors.toMap(
                    e -> e.getKey().name(), Map.Entry::getValue));
            Map<String, Integer> ceil = p.weeklySetCeiling() == null ? Map.of()
                : p.weeklySetCeiling().entrySet().stream().collect(Collectors.toMap(
                    e -> e.getKey().name(), Map.Entry::getValue));
            return new BlockParametersDto(p.mode().name(), p.expectedDriftPerDay(),
                p.successCriterion().name(), p.manualOverride(), reps, caps, ceil);
        }
    }

    public record BlockOverrideDto(String mode, String successCriterion, Double expectedDriftPerDay) {}

    public record EnergyBalanceDto(
        double maintenanceKcal, double meanIntakeKcal, double balanceKcal,
        String mode, boolean hasIntakeData) {
        static EnergyBalanceDto of(BlockLoop.EnergyBalance e) {
            return new EnergyBalanceDto(e.maintenanceKcal(), e.meanIntakeKcal(),
                e.balanceKcal(), e.mode().name(), e.hasIntakeData());
        }
    }

    public record ExerciseStrengthDto(
        String exerciseId, String name, String movementPattern,
        double e1rmLbs, String confidence, int observationCount,
        // IMPL-PROG-LOAD-01 (D3/D8): 1 or 2, and the pre-doubled total for
        // per-hand lifts so the strength view compares to barbell lifts.
        int loadFactor, double e1rmTotalLbs) {}

    /** IMPL-DELOAD-01 (D4): the per-lift progression audit envelope. */
    public record ProgressionLogDto(
        String exerciseId, String exerciseName, int loadFactor, List<ProgressionLogRowDto> rows) {}

    /**
     * One completed session's row in the audit: what the engine wanted
     * ({@code targetWeightLbs}/{@code loadBasis}/{@code rationaleInputs}) vs
     * what was performed (top set), plus provenance ({@code path}) and the
     * deload flag. Target fields are null for history completed before target
     * retention (P0) or for pre-engine sessions. {@code *TotalLbs} carry the
     * per-hand ×2 for dumbbell/dual-cable lifts (IMPL-PROG-LOAD-01 D9).
     */
    public record ProgressionLogRowDto(
        String date, String path, String direction,
        Double targetWeightLbs, String loadBasis, List<String> rationaleInputs,
        Double topSetWeightLbs, Integer topSetReps, int loggedSetCount, boolean isDeload,
        String programId, String scheduledId,
        Double targetTotalLbs, Double topSetTotalLbs) {}
}
