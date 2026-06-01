package com.gte619n.healthfitness.app.jobs;

import com.gte619n.healthfitness.app.workoutimport.FutureWorkoutsParser;
import com.gte619n.healthfitness.core.workoutimport.FutureWorkouts;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter.ImportResult;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Workout-history seeding Cloud Run Job (IMPL-15, ADR-0008). Imports
 * {@code future_workouts.json} — the name-only exercise catalog, the program +
 * 20 phases, and the completed-session history with logged weights — for one
 * user, enriching exercises via Gemini ({@code gemini-3.5-flash}).
 *
 * <p>Activation mirrors {@link SeedFoodCatalogJob}: this component only loads
 * under the Spring profile {@code job-seed-workouts}. The deployed Cloud Run Job
 * sets {@code SPRING_PROFILES_ACTIVE=job-seed-workouts}; the web service does
 * not, so the {@link CommandLineRunner} fires only inside the job execution.
 * Returning normally lets Spring shut the context down and the JVM exit 0. See
 * {@code infra/scripts/deploy-seed-workouts-job.sh}.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code app.workouts.seed.json-path} — path to future_workouts.json</li>
 *   <li>{@code app.workouts.seed.user-id} — Firestore user id (Google {@code sub})
 *       that owns the program + history</li>
 * </ul>
 * Idempotent: catalog writes skip existing ids; program/phase/session ids are
 * deterministic, so re-running upserts cleanly.
 */
@Component
@Profile("job-seed-workouts")
public class SeedWorkoutHistoryJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(SeedWorkoutHistoryJob.class.getName());

    private final WorkoutHistoryImporter importer;
    private final String jsonPath;
    private final String userId;

    public SeedWorkoutHistoryJob(
        WorkoutHistoryImporter importer,
        @Value("${app.workouts.seed.json-path:}") String jsonPath,
        @Value("${app.workouts.seed.user-id:}") String userId
    ) {
        this.importer = importer;
        this.jsonPath = jsonPath;
        this.userId = userId;
    }

    @Override
    public void run(String... args) {
        if (jsonPath == null || jsonPath.isBlank()) {
            log.log(System.Logger.Level.ERROR,
                "SeedWorkoutHistoryJob: app.workouts.seed.json-path is unset — nothing to do");
            return;
        }
        if (userId == null || userId.isBlank()) {
            log.log(System.Logger.Level.ERROR,
                "SeedWorkoutHistoryJob: app.workouts.seed.user-id is unset — refusing to seed");
            return;
        }
        log.log(System.Logger.Level.INFO,
            "SeedWorkoutHistoryJob: starting (path={0}, user={1})", jsonPath, userId);
        try {
            FutureWorkouts data = FutureWorkoutsParser.parse(Path.of(jsonPath));
            ImportResult r = importer.importAll(userId, data);
            log.log(System.Logger.Level.INFO,
                "SeedWorkoutHistoryJob: done — exercises seeded={0} skipped={1}, phases={2}, "
                    + "sessions={3}, skippedSessionExercises={4}, unresolvedEquipment={5}",
                r.exercisesSeeded(), r.exercisesSkipped(), r.phases(), r.sessions(),
                r.sessionExercisesSkipped(), r.unresolvedEquipmentNames());
        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR, "SeedWorkoutHistoryJob: failed: " + e.getMessage(), e);
            throw new IllegalStateException("workout-history seed failed", e);
        }
    }
}
