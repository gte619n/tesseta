package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The block loop (IMPL-PROG-01 §8): monthly / on-change, selects a training mode
 * from the 14-day rolling energy balance and sets the parameters the other loops
 * operate under. Touches NO loads. Labs/body-comp enter here and ONLY as gates
 * (§8.3) — never as a coefficient on a load.
 */
@Service
public class BlockLoop {

    // Energy-balance thresholds (kcal/day) → mode (§8.1). The 500 gate is the
    // Murphy & Koehler lean-mass-blunting deficit [Certain].
    static final double SURPLUS = 300;
    static final double DEFICIT_MAINTENANCE = -300;
    static final double DEFICIT_RECOVERY = -500;

    // Expected e1RM drift per day by mode (lb/day) [Guessing — tune vs §10].
    static final double DRIFT_GAINING = 0.10;
    static final double DRIFT_RECOMP = 0.0;
    static final double DRIFT_MAINTENANCE = 0.0;
    static final double DRIFT_RECOVERY = -0.05;

    private final MaintenanceCalorieEstimator maintenance;
    private final BlockParametersRepository blockParams;
    private final Clock clock;

    public BlockLoop(
        MaintenanceCalorieEstimator maintenance, BlockParametersRepository blockParams, Clock clock) {
        this.maintenance = maintenance;
        this.blockParams = blockParams;
        this.clock = clock;
    }

    /** Recompute and persist block parameters unless the user has pinned a manual override. */
    public BlockParameters recompute(String userId) {
        BlockParameters existing = blockParams.find(userId).orElse(null);
        if (existing != null && existing.manualOverride()) return existing;

        double meanIntake = maintenance.meanIntake14d(userId);
        double maintenanceKcal = maintenance.estimate(userId);
        double balance = meanIntake > 0 ? meanIntake - maintenanceKcal : 0; // no data → neutral RECOMP

        BlockMode mode = modeFor(balance);
        BlockParameters params = parametersFor(userId, mode);
        blockParams.save(params);
        return params;
    }

    static BlockMode modeFor(double balance) {
        if (balance >= SURPLUS) return BlockMode.GAINING;
        if (balance > DEFICIT_MAINTENANCE) return BlockMode.RECOMP;
        if (balance >= DEFICIT_RECOVERY) return BlockMode.MAINTENANCE;
        return BlockMode.RECOVERY;
    }

    /**
     * Read-only energy-balance snapshot (no persistence) for the plan-coherence
     * view: the measured maintenance TDEE, the 14-day mean intake, the resulting
     * balance, and the mode that balance implies. {@code hasIntakeData} is false
     * until enough intake has been logged — the balance is not meaningful yet and
     * mode falls back to RECOMP (same neutral default as {@link #recompute}).
     */
    public EnergyBalance energyBalance(String userId) {
        double meanIntake = maintenance.meanIntake14d(userId);
        double maintenanceKcal = maintenance.estimate(userId);
        boolean hasData = meanIntake > 0;
        double balance = hasData ? meanIntake - maintenanceKcal : 0;
        return new EnergyBalance(maintenanceKcal, meanIntake, balance, modeFor(balance), hasData);
    }

    /** Snapshot returned by {@link #energyBalance(String)}. */
    public record EnergyBalance(
        double maintenanceKcal, double meanIntakeKcal, double balanceKcal,
        BlockMode mode, boolean hasIntakeData) {}

    private BlockParameters parametersFor(String userId, BlockMode mode) {
        double drift = switch (mode) {
            case GAINING -> DRIFT_GAINING;
            case RECOMP -> DRIFT_RECOMP;
            case MAINTENANCE -> DRIFT_MAINTENANCE;
            case RECOVERY -> DRIFT_RECOVERY;
        };
        // In a deficit, flat performance is success and the week loop must not
        // deload FLAT trends (§8.1, D11) — carried by the success criterion.
        SuccessCriterion criterion = (mode == BlockMode.GAINING || mode == BlockMode.RECOMP)
            ? SuccessCriterion.ADD_LOAD : SuccessCriterion.HOLD_LOAD_AT_LOWER_RIR;
        int ceiling = switch (mode) {
            case GAINING -> 22;
            case RECOMP -> 18;
            case MAINTENANCE -> 14;
            case RECOVERY -> 10;
        };
        Map<MovementPattern, RepBand> reps = new EnumMap<>(MovementPattern.class);
        Map<MovementPattern, Integer> ceil = new EnumMap<>(MovementPattern.class);
        for (MovementPattern p : MovementPattern.values()) {
            reps.put(p, new RepBand(8, 12));
            ceil.put(p, ceiling);
        }
        Map<Mechanic, Double> caps = new EnumMap<>(Mechanic.class);
        caps.put(Mechanic.COMPOUND, 2.0);
        caps.put(Mechanic.ISOLATION, 1.0);
        return new BlockParameters(userId, mode, drift, reps, caps, ceil, criterion, false,
            clock.instant(), version(userId));
    }

    private long version(String userId) {
        return blockParams.find(userId).map(p -> p.version() + 1).orElse(1L);
    }
}
