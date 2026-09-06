package com.gte619n.healthfitness.api.progression;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.progression.BlockLoop;
import com.gte619n.healthfitness.core.progression.BlockMode;
import com.gte619n.healthfitness.core.progression.BlockParameters;
import com.gte619n.healthfitness.core.progression.ProgressionEngine;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import com.gte619n.healthfitness.core.progression.SuccessCriterion;
import com.gte619n.healthfitness.core.progression.WeekLoop;
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

    public ProgressionController(
        CurrentUserProvider currentUser, ProgressionEngine engine,
        ProgressionStateRepository states, ExerciseRepository exercises) {
        this.currentUser = currentUser;
        this.engine = engine;
        this.states = states;
        this.exercises = exercises;
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
        return all.stream()
            .filter(s -> s.e1rmLbs() > 0) // skip cold states with no belief yet
            .sorted(Comparator.comparingDouble(ProgressionState::e1rmLbs).reversed())
            .map(s -> {
                Exercise ex = byId.get(s.exerciseId());
                return new ExerciseStrengthDto(
                    s.exerciseId(),
                    ex != null ? ex.name() : s.exerciseId(),
                    ex != null ? ex.movementPattern().name() : null,
                    s.e1rmLbs(), s.confidence().name(), s.observationCount());
            })
            .toList();
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
        double e1rmLbs, String confidence, int observationCount) {}
}
