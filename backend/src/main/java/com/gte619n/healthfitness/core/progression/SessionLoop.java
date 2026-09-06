package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.ExerciseDigest;
import com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The session loop (IMPL-PROG-01 §6): on session completion, for each performed
 * progression-eligible exercise it (1) logs observations, (2) updates the e1RM
 * belief via the scalar Kalman filter, (3) writes a shadow PredictionLog for
 * both models, and (4) derives and writes the NEXT session's prescription
 * (deterministic during the 2-week warm-up / fallback, Kalman once live, D5).
 * Only the session loop touches load. Runs synchronously inside completion (D22).
 */
@Service
public class SessionLoop {

    /** Kalman goes live per exercise after this much training (D5). */
    static final int WARMUP_DAYS = 14;
    /** Below this observation count the exercise is in deterministic cold-start (§6.6). */
    static final int COLD_START_MIN_OBS = 6;
    /** Kalman output beyond ±this of last load fails the sanity band → fallback (§6.6). */
    static final double SANITY_BAND = 0.10;
    /** Wide seed uncertainty so day-one prescriptions self-correct fast (D6). */
    static final double SEED_SIGMA_FRACTION = 0.08;

    private static final Set<BlockType> ELIGIBLE_BLOCKS =
        EnumSet.of(BlockType.MAIN, BlockType.ACCESSORY, BlockType.CORE);

    private final ProgressionStateRepository states;
    private final SetObservationRepository observations;
    private final PredictionLogRepository predictions;
    private final LoadingProfileResolver profiles;
    private final BlockParametersRepository blockParams;
    private final WeekParametersRepository weekParams;
    private final ExerciseRepository exercises;
    private final ExercisePerformanceDigestService digests;
    private final ProgressionWriteback writeback;
    private final Clock clock;

    public SessionLoop(
        ProgressionStateRepository states,
        SetObservationRepository observations,
        PredictionLogRepository predictions,
        LoadingProfileResolver profiles,
        BlockParametersRepository blockParams,
        WeekParametersRepository weekParams,
        ExerciseRepository exercises,
        ExercisePerformanceDigestService digests,
        ProgressionWriteback writeback,
        Clock clock
    ) {
        this.states = states;
        this.observations = observations;
        this.predictions = predictions;
        this.profiles = profiles;
        this.blockParams = blockParams;
        this.weekParams = weekParams;
        this.exercises = exercises;
        this.digests = digests;
        this.writeback = writeback;
        this.clock = clock;
    }

    /** Process every eligible exercise in a just-COMPLETED session. Best-effort per exercise. */
    public void onSessionCompleted(String userId, ScheduledWorkout completed) {
        if (completed == null || completed.session() == null || completed.session().blocks() == null) return;
        BlockParameters block = blockParams.find(userId).orElseGet(() -> BlockParameters.defaults(userId));
        for (Block b : completed.session().blocks()) {
            if (b.type() == null || !ELIGIBLE_BLOCKS.contains(b.type()) || b.prescriptions() == null) continue;
            for (Prescription rx : b.prescriptions()) {
                if (rx.exerciseId() == null || rx.durationSeconds() != null) continue; // timed → skip (D21)
                if (rx.loggedSets() == null || rx.loggedSets().isEmpty()) continue;
                try {
                    processExercise(userId, completed, rx, block);
                } catch (RuntimeException ex) {
                    // One bad exercise must not abort the completion fan-out.
                }
            }
        }
    }

    private void processExercise(String userId, ScheduledWorkout completed, Prescription rx, BlockParameters block) {
        String exerciseId = rx.exerciseId();
        ExerciseLoadingProfile profile = profiles.resolve(userId, exerciseId);
        if (!profile.progressionEligible()) return;

        Exercise exercise = exercises.findById(exerciseId).orElse(null);
        MovementPattern pattern = exercise != null && exercise.movementPattern() != null
            ? exercise.movementPattern() : MovementPattern.OTHER;
        Mechanic mechanic = exercise != null && exercise.mechanic() != null
            ? exercise.mechanic() : Mechanic.COMPOUND;

        List<LoggedSet> sets = rx.loggedSets();
        String sessionId = completed.scheduledId();
        recordObservations(userId, sessionId, exerciseId, sets, profile);

        double workingLoad = SessionAnalysis.workingLoad(sets);
        LoggedSet lastSet = SessionAnalysis.lastWorkingSet(sets);
        Double observedE1rm = SessionAnalysis.observedE1rm(lastSet, profile.loadOffsetLbs());

        // ---- belief update (Kalman) ----
        ProgressionState prior = states.find(userId, exerciseId).orElse(null);
        ProgressionState priorForPrediction = prior; // may be null on cold start
        Instant now = clock.instant();
        ProgressionState updated;
        if (prior == null || prior.observationCount() == 0) {
            updated = seed(userId, exerciseId, observedE1rm, workingLoad, profile, now);
        } else {
            updated = applyUpdate(prior, observedE1rm, lastSet, block, now, profile);
        }
        states.save(updated);

        // ---- shadow prediction log (both models) ----
        logPredictions(userId, exerciseId, sessionId, priorForPrediction, rx, workingLoad, lastSet, block, pattern, mechanic, profile);

        // ---- next-session prescription ----
        PrescribedLoad next = derivePrescription(
            userId, exerciseId, updated, sets, workingLoad, rx, block, pattern, mechanic, profile, now);
        writeback.applyNextPrescription(userId, exerciseId, next, completed.date());
    }

    // ---- observations ----

    private void recordObservations(
        String userId, String sessionId, String exerciseId, List<LoggedSet> sets, ExerciseLoadingProfile profile) {
        LoggedSet last = SessionAnalysis.lastWorkingSet(sets);
        List<SetObservation> batch = new ArrayList<>();
        int idx = 1;
        for (LoggedSet s : sets) {
            RirSource source = s.rirSource() != null ? s.rirSource()
                : (s.effectiveRir() != null ? RirSource.REPORTED : RirSource.ABSENT);
            batch.add(new SetObservation(
                UUID.randomUUID().toString(), userId, sessionId, exerciseId, idx++,
                s == last, s.weightLbs() == null ? 0 : s.weightLbs(), s.reps(),
                source, s.effectiveRir(), null, s.completedAt(), Set.of()));
        }
        observations.saveAll(batch);
    }

    // ---- seeding & update ----

    private ProgressionState seed(
        String userId, String exerciseId, Double observedE1rm, double workingLoad,
        ExerciseLoadingProfile profile, Instant now) {
        double e1rm = 0;
        Map<String, ExerciseDigest> digestMap = digests.digest(userId, List.of(exerciseId));
        ExerciseDigest digest = digestMap.get(exerciseId);
        if (digest != null && digest.estimated1Rm() != null) {
            e1rm = digest.estimated1Rm();                       // seed from real history (D6)
        } else if (observedE1rm != null) {
            e1rm = observedE1rm;
        } else if (workingLoad > 0) {
            e1rm = workingLoad + profile.loadOffsetLbs();
        }
        double sigma = Math.max(1.0, SEED_SIGMA_FRACTION * e1rm);
        Instant eligible = now.plus(Duration.ofDays(WARMUP_DAYS));
        return new ProgressionState(userId, exerciseId, e1rm, sigma, now, 1, 0, eligible, 1);
    }

    private ProgressionState applyUpdate(
        ProgressionState prior, Double observedE1rm, LoggedSet lastSet,
        BlockParameters block, Instant now, ExerciseLoadingProfile profile) {
        if (observedE1rm == null) {
            // No usable RIR: grow uncertainty for the elapsed time, don't correct.
            double days = daysBetween(prior.lastObservedAt(), now);
            double sigma = KalmanUpdate.driftSigma(prior.e1rmLbs(), prior.sigmaLbs(), days);
            return new ProgressionState(prior.userId(), prior.exerciseId(), prior.e1rmLbs(), sigma,
                now, prior.observationCount() + 1, 0,
                prior.kalmanEligibleAt() == null ? now.plus(Duration.ofDays(WARMUP_DAYS)) : prior.kalmanEligibleAt(),
                prior.version() + 1);
        }
        double days = daysBetween(prior.lastObservedAt(), now);
        double totalReps = SessionAnalysis.totalCapableReps(lastSet);
        RirSource source = lastSet.rirSource() != null ? lastSet.rirSource() : RirSource.INFERRED_TARGET;
        double obsSd = ObservationNoiseModel.observationSd(
            prior.e1rmLbs(), totalReps, source, lastSet.effectiveRir(), true, Set.of());
        KalmanUpdate.Result r = KalmanUpdate.step(
            prior.e1rmLbs(), prior.sigmaLbs(), block.expectedDriftPerDay(), days, observedE1rm, obsSd);
        Instant eligible = prior.kalmanEligibleAt() == null
            ? now.plus(Duration.ofDays(WARMUP_DAYS)) : prior.kalmanEligibleAt();
        return new ProgressionState(prior.userId(), prior.exerciseId(), r.e1rmLbs(), r.sigmaLbs(),
            now, prior.observationCount() + 1, 0, eligible, prior.version() + 1);
    }

    // ---- prescription path selection ----

    private PrescribedLoad derivePrescription(
        String userId, String exerciseId, ProgressionState state, List<LoggedSet> sets,
        double workingLoad, Prescription completedRx, BlockParameters block,
        MovementPattern pattern, Mechanic mechanic, ExerciseLoadingProfile profile, Instant now) {

        RepBand band = block.repBand(pattern);
        double targetRir = block.rirCap(mechanic);
        int sets_ = weeklyTargetSets(userId, pattern, completedRx.sets() == null ? sets.size() : completedRx.sets());

        boolean warmUp = state.kalmanEligibleAt() == null || now.isBefore(state.kalmanEligibleAt());
        boolean cold = state.observationCount() < COLD_START_MIN_OBS;

        // Deterministic path (warm-up or cold-start fallback).
        if (warmUp || cold) {
            boolean priorFailed = priorSessionFailedBottom(userId, exerciseId, band, sets);
            List<Integer> repsAtLoad = SessionAnalysis.repsAtWorkingLoad(sets, workingLoad);
            PrescribedLoad dp = DoubleProgression.next(
                workingLoad, repsAtLoad, band, profile.loadIncrementLbs(), priorFailed, sets_);
            return relabelPath(dp, warmUp ? ProgressionPath.WARMUP
                : ProgressionPath.FALLBACK_COLD_START);
        }

        // Kalman path with a ±10% sanity band → fallback.
        double lastPrescribed = completedRx.targetWeightLbs() != null ? completedRx.targetWeightLbs() : workingLoad;
        PrescribedLoad kalman = PrescriptionCalculator.calculate(
            state, band, targetRir, profile.loadOffsetLbs(), profile.loadIncrementLbs(),
            lastPrescribed, workingLoad, sets_);
        if (workingLoad > 0 && Math.abs(kalman.targetWeightLbs() - workingLoad) > SANITY_BAND * workingLoad) {
            List<Integer> repsAtLoad = SessionAnalysis.repsAtWorkingLoad(sets, workingLoad);
            boolean priorFailed = priorSessionFailedBottom(userId, exerciseId, band, sets);
            PrescribedLoad dp = DoubleProgression.next(
                workingLoad, repsAtLoad, band, profile.loadIncrementLbs(), priorFailed, sets_);
            return relabelPath(dp, ProgressionPath.FALLBACK_SANITY);
        }
        return kalman;
    }

    private int weeklyTargetSets(String userId, MovementPattern pattern, int fallbackPerSession) {
        return weekParams.find(userId)
            .map(w -> {
                if (w.isDeloadActive(pattern)) return Math.max(1, fallbackPerSession / 2); // deload halves sets
                return fallbackPerSession;
            })
            .orElse(fallbackPerSession);
    }

    /** Did the session immediately before this one miss the bottom of the range? */
    private boolean priorSessionFailedBottom(String userId, String exerciseId, RepBand band, List<LoggedSet> currentSets) {
        List<SetObservation> all = observations.findByExercise(userId, exerciseId);
        // Group by session, find the latest session that isn't the current one.
        String currentSession = all.isEmpty() ? null : all.get(all.size() - 1).sessionId();
        String priorSession = null;
        Instant priorTime = null;
        for (SetObservation o : all) {
            if (currentSession != null && currentSession.equals(o.sessionId())) continue;
            if (o.completedAt() == null) continue;
            if (priorTime == null || o.completedAt().isAfter(priorTime)) {
                priorTime = o.completedAt();
                priorSession = o.sessionId();
            }
        }
        if (priorSession == null) return false;
        for (SetObservation o : all) {
            if (priorSession.equals(o.sessionId()) && o.reps() != null && o.reps() < band.min()) return true;
        }
        return false;
    }

    private static PrescribedLoad relabelPath(PrescribedLoad load, ProgressionPath path) {
        PrescriptionRationale r = load.rationale();
        PrescriptionRationale relabeled = new PrescriptionRationale(
            path, r.direction(), r.deltaLbs(), r.deltaReps(), r.deltaSets(), r.confidence(), r.inputs());
        return new PrescribedLoad(load.sets(), load.repsMin(), load.repsMax(), load.targetWeightLbs(), relabeled);
    }

    // ---- shadow predictions ----

    private void logPredictions(
        String userId, String exerciseId, String sessionId, ProgressionState priorBelief,
        Prescription completedRx, double workingLoad, LoggedSet lastSet, BlockParameters block,
        MovementPattern pattern, Mechanic mechanic, ExerciseLoadingProfile profile) {
        if (lastSet == null || lastSet.reps() == null) return;
        double prescribedLoad = completedRx.targetWeightLbs() != null ? completedRx.targetWeightLbs() : workingLoad;
        if (prescribedLoad <= 0) return;
        int actualReps = lastSet.reps();
        double targetRir = block.rirCap(mechanic);
        Instant now = clock.instant();

        // Kalman model: reps it predicted at the prescribed load from the PRIOR belief.
        if (priorBelief != null && priorBelief.e1rmLbs() > 0) {
            double kPred = predictedReps(priorBelief.e1rmLbs(), prescribedLoad + profile.loadOffsetLbs(), targetRir);
            predictions.save(new PredictionLog(UUID.randomUUID().toString(), userId, exerciseId, sessionId,
                "kalman_v1", prescribedLoad, kPred, targetRir, actualReps, lastSet.effectiveRir(),
                Math.abs(kPred - actualReps), now));
        }
        // Double-progression model: naive persistence — predicts the band midpoint.
        double dpPred = block.repBand(pattern).mid();
        predictions.save(new PredictionLog(UUID.randomUUID().toString(), userId, exerciseId, sessionId,
            "double_progression", prescribedLoad, dpPred, targetRir, actualReps, lastSet.effectiveRir(),
            Math.abs(dpPred - actualReps), now));
    }

    /** Reps predicted at a load given e1RM belief and target RIR (inverse Epley). */
    static double predictedReps(double e1rm, double loadPlusOffset, double targetRir) {
        if (loadPlusOffset <= 0) return 0;
        double totalReps = 30.0 * (e1rm / loadPlusOffset - 1.0);
        return Math.max(0, totalReps - targetRir);
    }

    private static double daysBetween(Instant a, Instant b) {
        if (a == null || b == null) return 0;
        return Math.max(0, ChronoUnit.SECONDS.between(a, b) / 86400.0);
    }
}
