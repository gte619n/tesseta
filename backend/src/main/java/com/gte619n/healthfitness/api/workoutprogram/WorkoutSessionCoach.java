package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.BlockResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.PrescriptionResponse;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient.ExerciseLine;
import com.gte619n.healthfitness.integrations.workoutprogram.WorkoutCoachClient.SessionRecap;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the post-workout AI recap (IMPL-COACH) for a just-completed session.
 * Distills the assembled {@link ScheduledWorkoutResponse} — which already
 * carries resolved exercise names and the logged actuals — into the compact
 * {@link SessionRecap} the {@link WorkoutCoachClient} speaks to. Best-effort:
 * returns {@code null} for non-completed sessions or when the coach is
 * unavailable, so the completion response is unaffected.
 */
@Component
public class WorkoutSessionCoach {

    private final WorkoutCoachClient client;

    public WorkoutSessionCoach(WorkoutCoachClient client) {
        this.client = client;
    }

    /** A recap for a just-completed session, or {@code null} when not applicable. */
    public String recapFor(ScheduledWorkoutResponse session) {
        if (session == null || session.status() != ScheduledStatus.COMPLETED) {
            return null;
        }
        return client.generateRecap(toRecap(session));
    }

    static SessionRecap toRecap(ScheduledWorkoutResponse r) {
        List<ExerciseLine> lines = new ArrayList<>();
        double totalVolume = 0.0;
        int setsCompleted = 0;
        int setsPrescribed = 0;

        DayResponse session = r.session();
        if (session != null && session.blocks() != null) {
            for (BlockResponse block : session.blocks()) {
                if (block.prescriptions() == null) continue;
                for (PrescriptionResponse rx : block.prescriptions()) {
                    if (rx.sets() != null) {
                        setsPrescribed += rx.sets();
                    }
                    List<LoggedSet> logged = rx.loggedSets() == null ? List.of() : rx.loggedSets();
                    if (logged.isEmpty()) continue;

                    Double topWeight = null;
                    Integer topReps = null;
                    double rpeSum = 0.0;
                    int rpeCount = 0;
                    for (LoggedSet set : logged) {
                        if (set == null) continue;
                        setsCompleted++;
                        if (set.weightLbs() != null && set.reps() != null) {
                            totalVolume += set.weightLbs() * set.reps();
                        }
                        if (set.weightLbs() != null && (topWeight == null || set.weightLbs() > topWeight)) {
                            topWeight = set.weightLbs();
                            topReps = set.reps();
                        }
                        if (set.rpe() != null) {
                            rpeSum += set.rpe();
                            rpeCount++;
                        }
                    }
                    String name = rx.exercise() != null ? rx.exercise().name() : rx.exerciseId();
                    lines.add(new ExerciseLine(
                        name, logged.size(), topWeight, topReps,
                        rpeCount > 0 ? rpeSum / rpeCount : null));
                }
            }
        }
        return new SessionRecap(
            r.dayLabel(),
            r.durationSeconds() == null ? 0 : r.durationSeconds(),
            totalVolume, setsCompleted, setsPrescribed, lines);
    }
}
