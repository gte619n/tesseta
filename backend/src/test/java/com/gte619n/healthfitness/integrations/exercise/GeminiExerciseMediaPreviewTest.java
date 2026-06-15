package com.gte619n.healthfitness.integrations.exercise;

import com.gte619n.healthfitness.core.exercise.DemoPhase;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.google.genai.Client;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live-Gemini preview of exercise demo-image generation (IMPL-15, ADR-0008).
 * Disabled in CI — only runs when {@code GEMINI_API_KEY} is set. Generates the
 * START/MID/END demo stills for a few sample movements with the real
 * {@code gemini-3.1-flash-image-preview} model and writes the PNGs to
 * {@code docs/test_reports/workout_logs/media_preview/} so image quality can be
 * judged before the full backfill job runs.
 *
 * <p>Run: {@code GEMINI_API_KEY=… ./gradlew :integrations:test --tests *GeminiExerciseMediaPreview*}
 *
 * <p>Same package as {@link GeminiExerciseMediaService} to drive its
 * package-private {@code buildPrompt} + {@code callGemini} directly, bypassing
 * GCS upload (we write bytes to disk instead).
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiExerciseMediaPreviewTest {

    // Three movement samples (the "Recover" rest placeholder has no demo).
    private static final List<Sample> SAMPLES = List.of(
        new Sample("Barbell Bench Press", MovementPattern.PUSH_HORIZONTAL, Laterality.BILATERAL,
            List.of("Retract your shoulder blades and pin them to the bench",
                "Keep your feet flat on the floor",
                "Lower the bar to your mid-chest under control")),
        new Sample("Dumbbell Goblet Squat", MovementPattern.SQUAT, Laterality.BILATERAL,
            List.of("Hold the dumbbell vertically close to your chest",
                "Keep your elbows tucked and chest up",
                "Push your knees out as you sink your hips")),
        new Sample("Single-Arm Dumbbell Row", MovementPattern.PULL_HORIZONTAL, Laterality.UNILATERAL,
            List.of("Keep your spine neutral and flat",
                "Pull the dumbbell toward your hip, not your chest",
                "Drive your elbow toward the ceiling")));

    private record Sample(String name, MovementPattern pattern, Laterality laterality, List<String> cues) {}

    @Test
    void previewDemoImages() throws Exception {
        GeminiExerciseMediaService media = new GeminiExerciseMediaService(
            null, null, new GroundingImageResolver(false),
            Client.builder().apiKey(System.getenv("GEMINI_API_KEY")).build(),
            "gemini-3.1-flash-image-preview", false);

        Path outDir = locate("docs/test_reports/workout_logs").resolve("media_preview");
        Files.createDirectories(outDir);

        // Optional comma-separated slug filter (e.g. MEDIA_SAMPLE_SLUGS=barbell-bench-press).
        String only = System.getenv("MEDIA_SAMPLE_SLUGS");
        List<String> onlySlugs = (only == null || only.isBlank()) ? List.of() : List.of(only.split(","));

        int ok = 0;
        for (Sample s : SAMPLES) {
            if (!onlySlugs.isEmpty() && !onlySlugs.contains(slug(s.name()))) {
                continue;
            }
            Exercise exercise = exerciseFor(s);
            for (DemoPhase phase : DemoPhase.values()) {
                byte[] bytes = media.callGemini(media.buildPrompt(exercise, phase, null), null, exercise.exerciseId());
                if (bytes == null || bytes.length == 0) {
                    System.out.println("FAILED: no bytes for " + s.name() + " " + phase);
                    continue;
                }
                Path out = outDir.resolve(slug(s.name()) + "_" + phase.name().toLowerCase() + ".png");
                Files.write(out, bytes);
                ok++;
                System.out.println("Wrote " + out.toAbsolutePath() + " (" + bytes.length + " bytes)");
            }
        }
        System.out.println("Generated " + ok + " demo image(s) to " + outDir.toAbsolutePath());
    }

    private static Exercise exerciseFor(Sample s) {
        Instant now = Instant.now();
        return new Exercise(
            slug(s.name()), s.name(), s.name().toLowerCase(), List.of(),
            s.pattern(), List.of(), List.of(), s.laterality(), Mechanic.COMPOUND,
            null, s.cues(), List.of(), List.of(), null, false,
            List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, now, now, null);
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static Path locate(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate " + relative);
    }
}
