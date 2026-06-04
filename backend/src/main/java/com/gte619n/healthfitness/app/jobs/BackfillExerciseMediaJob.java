package com.gte619n.healthfitness.app.jobs;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaGenerator;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Exercise demo-media backfill Cloud Run Job (IMPL-15, ADR-0008). Seeded
 * exercises are imported without media ({@code mediaStatus = NONE}); this job
 * generates START/MID/END demo stills for them via the existing
 * {@link ExerciseMediaGenerator} (Gemini {@code gemini-3.1-flash-image-preview}),
 * a bounded batch per run so we don't fire hundreds of image calls at once.
 *
 * <p>Activation mirrors {@link BackfillFoodImagesJob}: this component only loads
 * under the Spring profile {@code job-exercise-media-backfill}. Generated media
 * lands {@code NEEDS_REVIEW} (the anatomical-correctness gate); an admin then
 * approves it in {@code /admin/exercises/review}. Re-running picks up exercises
 * still at {@code NONE}, so repeated runs converge. See
 * {@code infra/scripts/deploy-exercise-media-job.sh}.
 */
@Component
@Profile("job-exercise-media-backfill")
public class BackfillExerciseMediaJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(BackfillExerciseMediaJob.class.getName());

    private final ExerciseRepository exercises;
    private final ExerciseMediaGenerator media;
    private final int limit;

    public BackfillExerciseMediaJob(
        ExerciseRepository exercises,
        ExerciseMediaGenerator media,
        @Value("${app.exercises.media-backfill-limit:50}") int limit
    ) {
        this.exercises = exercises;
        this.media = media;
        this.limit = limit;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO,
            "BackfillExerciseMediaJob: starting (limit={0})", limit);
        List<Exercise> pending = exercises.findByMediaStatus(ExerciseMediaStatus.NONE);
        int enqueued = 0;
        for (Exercise e : pending) {
            if (enqueued >= limit) {
                break;
            }
            // generateDemoAsync flips status to PENDING then NEEDS_REVIEW on
            // completion; we block on each so a bounded job run doesn't exit
            // (and shut the context down) before the async work finishes.
            try {
                media.generateDemoAsync(e).join();
                enqueued++;
            } catch (Exception ex) {
                log.log(System.Logger.Level.WARNING,
                    "BackfillExerciseMediaJob: generation failed for {0}: {1}",
                    e.exerciseId(), ex.getMessage());
            }
        }
        log.log(System.Logger.Level.INFO,
            "BackfillExerciseMediaJob: done — generated demo media for {0} exercise(s) "
                + "({1} still pending at NONE)", enqueued, Math.max(0, pending.size() - enqueued));
    }
}
