package com.gte619n.healthfitness.core.progression;

import java.time.Instant;
import java.util.Set;

/**
 * An append-only record of one performed set as the engine saw it (IMPL-PROG-01
 * §5). Written for every completed set of a progression-eligible exercise; the
 * substrate the replay harness and (later) the RIR-gaming monitor read.
 *
 * <p>{@code load} already includes nothing extra — it is the bar/stack load
 * lifted; the engine adds {@code loadOffset} when computing e1RM. {@code rir}
 * carries the effective RIR (reported or converted from RPE). {@code contextFlags}
 * is present but unpopulated beyond the feeling mapping in v1 (D14/D20).
 */
public record SetObservation(
    String id,
    String userId,
    String sessionId,
    String exerciseId,
    int setIndex,                   // 1-based within the exercise
    boolean isLastWorkingSet,
    double load,
    Integer reps,
    RirSource rirSource,
    Double rir,
    Double meanConcentricVelocity, // reserved; null in v1 (D14)
    Instant completedAt,
    Set<ContextFlag> contextFlags
) {}
