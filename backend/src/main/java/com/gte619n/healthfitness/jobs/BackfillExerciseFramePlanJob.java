package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseFramePlanner;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.ExerciseService;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Exercise frame-plan backfill Cloud Run Job (IMPL-19). Mirrors
 * {@link BackfillExerciseMediaJob}: finds exercises whose frame plan is still at
 * {@code planStatus = NONE} and runs the {@link ExerciseFramePlanner}
 * ({@code gemini-3.5-flash}) for a bounded batch per run
 * ({@code app.exercises.plan-backfill-limit}, default 50). The planner result is
 * saved via {@link ExerciseService#savePlan}, which lands the plan at
 * {@code NEEDS_REVIEW} (the cheap human plan-review gate that precedes the
 * costlier media generation). Re-running picks up exercises still at
 * {@code NONE}, so repeated runs converge.
 *
 * <p>Activation mirrors {@link BackfillExerciseMediaJob}: this component only
 * loads under the Spring profile {@code job-exercise-plan-backfill}. The planner
 * requires {@code GEMINI_API_KEY}; without it {@code plan()} throws and that
 * exercise is logged and skipped.
 */
@Component
@Profile("job-exercise-plan-backfill")
public class BackfillExerciseFramePlanJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(BackfillExerciseFramePlanJob.class.getName());

    private final ExerciseRepository exercises;
    private final ExerciseService service;
    private final ExerciseFramePlanner planner;
    private final int limit;

    public BackfillExerciseFramePlanJob(
        ExerciseRepository exercises,
        ExerciseService service,
        ExerciseFramePlanner planner,
        @Value("${app.exercises.plan-backfill-limit:50}") int limit
    ) {
        this.exercises = exercises;
        this.service = service;
        this.planner = planner;
        this.limit = limit;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO,
            "BackfillExerciseFramePlanJob: starting (limit={0})", limit);
        List<Exercise> pending = exercises.findByPlanStatus(ExerciseMediaStatus.NONE);
        int planned = 0;
        for (Exercise e : pending) {
            if (planned >= limit) {
                break;
            }
            try {
                service.updatePlanStatus(e.exerciseId(), ExerciseMediaStatus.PENDING);
                List<FrameSpec> frames = planner.plan(e, null);
                service.savePlan(e.exerciseId(), frames);
                planned++;
            } catch (Exception ex) {
                log.log(System.Logger.Level.WARNING,
                    "BackfillExerciseFramePlanJob: planning failed for {0}: {1}",
                    e.exerciseId(), ex.getMessage());
                try {
                    service.updatePlanStatus(e.exerciseId(), ExerciseMediaStatus.FAILED);
                } catch (Exception ignored) {
                    // best-effort status update
                }
            }
        }
        log.log(System.Logger.Level.INFO,
            "BackfillExerciseFramePlanJob: done — planned {0} exercise(s) "
                + "({1} still pending at NONE)", planned, Math.max(0, pending.size() - planned));
    }
}
