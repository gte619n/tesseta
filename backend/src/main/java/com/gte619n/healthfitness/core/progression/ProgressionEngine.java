package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Facade over the three loops (IMPL-PROG-01 §2). On session completion it
 * refreshes the block/week envelope opportunistically (throttled — decision P3,
 * no cross-user scheduler needed at n=1), then runs the session loop. Also the
 * read/override surface the controller uses.
 */
@Service
public class ProgressionEngine {

    private static final Duration BLOCK_STALE = Duration.ofDays(7);
    private static final Duration WEEK_STALE = Duration.ofDays(3);

    private final BlockLoop blockLoop;
    private final WeekLoop weekLoop;
    private final SessionLoop sessionLoop;
    private final BlockParametersRepository blockParams;
    private final WeekParametersRepository weekParams;
    private final ProgressionStateRepository states;
    private final Clock clock;

    public ProgressionEngine(
        BlockLoop blockLoop,
        WeekLoop weekLoop,
        SessionLoop sessionLoop,
        BlockParametersRepository blockParams,
        WeekParametersRepository weekParams,
        ProgressionStateRepository states,
        Clock clock
    ) {
        this.blockLoop = blockLoop;
        this.weekLoop = weekLoop;
        this.sessionLoop = sessionLoop;
        this.blockParams = blockParams;
        this.weekParams = weekParams;
        this.states = states;
        this.clock = clock;
    }

    /** Entry point from the completion listener (D22). */
    public void onSessionCompleted(String userId, ScheduledWorkout session) {
        refreshEnvelopeIfStale(userId);
        sessionLoop.onSessionCompleted(userId, session);
    }

    private void refreshEnvelopeIfStale(String userId) {
        Instant now = clock.instant();
        Optional<BlockParameters> block = blockParams.find(userId);
        if (block.isEmpty() || block.get().computedAt() == null
            || block.get().computedAt().isBefore(now.minus(BLOCK_STALE))) {
            blockLoop.recompute(userId);
        }
        Optional<WeekParameters> week = weekParams.find(userId);
        if (week.isEmpty() || week.get().computedAt() == null
            || week.get().computedAt().isBefore(now.minus(WEEK_STALE))) {
            weekLoop.recompute(userId);
        }
    }

    // ---- read / override surface (controller) ----

    public Optional<ProgressionState> state(String userId, String exerciseId) {
        return states.find(userId, exerciseId);
    }

    public List<WeekLoop.PatternReview> weekReview(String userId) {
        return weekLoop.review(userId);
    }

    public BlockParameters blockParameters(String userId) {
        return blockParams.find(userId).orElseGet(() -> blockLoop.recompute(userId));
    }

    /** Measured energy-balance snapshot (read-only) for the plan-coherence view. */
    public BlockLoop.EnergyBalance energyBalance(String userId) {
        return blockLoop.energyBalance(userId);
    }

    /** Manual mode override (D12): pins params so the block loop won't overwrite. */
    public BlockParameters overrideBlockParameters(String userId, BlockParameters params) {
        BlockParameters pinned = new BlockParameters(
            userId, params.mode(), params.expectedDriftPerDay(), params.repRangesByPattern(),
            params.rirCapsByMechanic(), params.weeklySetCeiling(), params.successCriterion(),
            true, clock.instant(),
            blockParams.find(userId).map(p -> p.version() + 1).orElse(1L));
        blockParams.save(pinned);
        return pinned;
    }

    /** Manual reset (§12): forks state to cold, retaining observation history. */
    public void resetState(String userId, String exerciseId) {
        states.delete(userId, exerciseId);
    }
}
