package com.gte619n.healthfitness.workoutimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.app.workoutimport.FutureWorkoutsParser;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.workoutimport.FutureWorkouts;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter;
import com.gte619n.healthfitness.core.workoutimport.WorkoutHistoryImporter.ImportResult;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramAssembler;
import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Hermetic seed-mapping test for the workout-history import (IMPL-15, ADR-0008).
 * Runs {@link WorkoutHistoryImporter} over the real {@code future_workouts.json}
 * with in-memory repos and a deterministic fake enricher (no live Gemini /
 * Firestore), asserts the mapping invariants, and writes
 * {@code docs/test_reports/workout_logs/seed_preview.json} — the exact Firestore
 * document shapes — so the output can be examined before the real job runs.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class WorkoutHistorySeedMappingTest {

    private static final String USER = "seed-preview-user";

    @Autowired WorkoutHistoryImporter importer;
    @Autowired ExerciseRepository exercises;
    @Autowired WorkoutProgramRepository programs;
    @Autowired ScheduledWorkoutRepository scheduled;
    @Autowired WorkoutProgramAssembler assembler;
    @Autowired ObjectMapper objectMapper;

    @Test
    void mapsCatalogPhasesAndHistoryThenWritesPreview() throws Exception {
        Path json = locate("docs/test_reports/workout_logs/future_workouts.json");
        FutureWorkouts data = FutureWorkoutsParser.parse(json);

        ImportResult result = importer.importAll(USER, data);

        // ---- catalog ----
        assertThat(exercises.findAll())
            .as("every source exercise seeded under its source id")
            .hasSize(data.exercises().size());
        for (FutureWorkouts.CatalogExercise src : data.exercises()) {
            assertThat(exercises.findById(src.id()))
                .as("exercise %s seeded with source UUID", src.id())
                .isPresent();
        }

        // ---- program + phases ----
        long distinctPhases = data.workouts().stream()
            .map(FutureWorkouts.Session::phaseId).distinct().count();
        Optional<WorkoutProgram> program = programs.findById(USER, WorkoutHistoryImporter.PROGRAM_ID);
        assertThat(program).isPresent();
        List<ProgramPhase> phases = program.get().phases();
        assertThat(phases).hasSize((int) distinctPhases);
        assertThat(result.phases()).isEqualTo((int) distinctPhases);
        // phases ordered by start date (non-decreasing)
        for (int i = 1; i < phases.size(); i++) {
            assertThat(phases.get(i).targetStartDate())
                .as("phase %d starts on/after phase %d", i, i - 1)
                .isAfterOrEqualTo(phases.get(i - 1).targetStartDate());
            assertThat(phases.get(i).orderIndex()).isEqualTo(i);
        }

        // ---- history ----
        List<ScheduledWorkout> sessions = scheduled.findByProgram(
            USER, WorkoutHistoryImporter.PROGRAM_ID, LocalDate.MIN, LocalDate.MAX);
        long parseableSessions = data.workouts().stream()
            .filter(s -> s.completedTime() != null && !s.completedTime().isBlank())
            .count();
        assertThat(sessions).hasSize((int) parseableSessions);
        assertThat(result.sessions()).isEqualTo((int) parseableSessions);

        // every logged prescription references a real catalog exercise; nothing null
        long sourceSets = 0;
        int loggedSetsTotal = 0;
        for (ScheduledWorkout sw : sessions) {
            for (Block b : sw.session().blocks()) {
                for (Prescription rx : b.prescriptions()) {
                    assertThat(rx.exerciseId()).isNotNull();
                    assertThat(exercises.findById(rx.exerciseId())).isPresent();
                    assertThat(rx.loggedSets()).isNotNull();
                    loggedSetsTotal += rx.loggedSets().size();
                    for (LoggedSet ls : rx.loggedSets()) {
                        // weight-only source: reps must stay null, nothing invented
                        assertThat(ls.reps()).isNull();
                    }
                }
            }
        }
        for (FutureWorkouts.Session s : data.workouts()) {
            if (s.exercises() == null) continue;
            for (FutureWorkouts.SessionExercise se : s.exercises()) {
                if (se.exerciseId() == null || se.exerciseId().isBlank()) continue;
                sourceSets += se.sets() == null ? 0 : se.sets().size();
            }
        }
        assertThat((long) loggedSetsTotal)
            .as("logged sets preserved 1:1 from source (excluding null-id entries)")
            .isEqualTo(sourceSets);

        // ---- performed-session timing (Workout History) ----
        // Each session carries the finish timestamp and elapsed duration the
        // history view shows; completedAt's date matches the session date.
        for (ScheduledWorkout sw : sessions) {
            assertThat(sw.completedAt())
                .as("session %s has a finish timestamp", sw.scheduledId())
                .isNotNull();
            assertThat(sw.completedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate())
                .isEqualTo(sw.date());
        }
        assertThat(sessions).anySatisfy(sw ->
            assertThat(sw.durationSeconds()).as("at least one session records a duration").isNotNull());

        // ---- program view derives days from sessions ----
        // Storage keeps phases template-free (lean docs); the deep response fills
        // each empty phase from its performed sessions so the program view isn't blank.
        assertThat(phases).allSatisfy(ph ->
            assertThat(ph.days()).as("phase %s stays template-free in storage", ph.phaseId()).isEmpty());
        WorkoutProgramDeepResponse deep = assembler.deep(program.get(), sessions);
        int derivedDays = deep.phases().stream().mapToInt(ph -> ph.days().size()).sum();
        assertThat(derivedDays)
            .as("every performed session surfaces as a day in the program view")
            .isEqualTo(sessions.size());
        assertThat(deep.phases())
            .as("no phase renders empty once sessions are derived")
            .allSatisfy(ph -> assertThat(ph.days()).isNotEmpty());

        // ---- examinable preview artifact ----
        writePreview(json.getParent(), data, result, program.get(), sessions);
    }

    private void writePreview(
        Path outDir, FutureWorkouts data, ImportResult result,
        WorkoutProgram program, List<ScheduledWorkout> sessions) throws Exception {

        List<Exercise> sampleExercises = new ArrayList<>();
        for (FutureWorkouts.CatalogExercise src : data.exercises()) {
            exercises.findById(src.id()).ifPresent(sampleExercises::add);
            if (sampleExercises.size() >= 5) break;
        }
        List<ScheduledWorkout> sampleSessions = sessions.stream()
            .sorted(Comparator.comparing(ScheduledWorkout::date))
            .limit(10)
            .toList();

        Map<String, Object> preview = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("exercisesSeeded", result.exercisesSeeded());
        summary.put("exercisesSkipped", result.exercisesSkipped());
        summary.put("phases", result.phases());
        summary.put("sessions", result.sessions());
        summary.put("sessionExercisesSkipped", result.sessionExercisesSkipped());
        summary.put("unresolvedEquipmentNames", result.unresolvedEquipmentNames());
        summary.put("note", "Enrichment here is the deterministic offline fake; the real job uses gemini-3.5-flash.");
        preview.put("summary", summary);
        preview.put("sampleExercises", sampleExercises);
        preview.put("program", program);
        preview.put("sampleCompletedSessions", sampleSessions);

        Path out = outDir.resolve("seed_preview.json");
        Files.createDirectories(out.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), preview);
        System.out.println("Wrote seed preview to " + out.toAbsolutePath());
    }

    /** Walk up from the working dir to find a repo-relative path. */
    private static Path locate(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
