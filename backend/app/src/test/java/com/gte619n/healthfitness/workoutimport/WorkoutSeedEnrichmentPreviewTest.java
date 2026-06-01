package com.gte619n.healthfitness.workoutimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.integrations.exercise.GeminiExerciseMetadataEnricher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live-Gemini preview of exercise metadata enrichment (IMPL-15, ADR-0008).
 * Disabled in CI — only runs when {@code GEMINI_API_KEY} is set. Enriches a few
 * representative exercises with the real {@code gemini-3.5-flash} enricher and
 * writes {@code docs/test_reports/workout_logs/enrichment_preview.json} so the
 * AI output quality (and cost) can be judged before the full 352× job run.
 *
 * <p>Run: {@code GEMINI_API_KEY=… ./gradlew :app:test --tests *WorkoutSeedEnrichmentPreview*}
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class WorkoutSeedEnrichmentPreviewTest {

    private static final List<String> SAMPLES = List.of(
        "Barbell Bench Press",
        "Dumbbell Goblet Squat",
        "Single-Arm Dumbbell Row",
        "Recover");

    @Test
    void previewRealEnrichment() throws Exception {
        ExerciseMetadataEnricher enricher = new GeminiExerciseMetadataEnricher(
            System.getenv("GEMINI_API_KEY"), "gemini-3.5-flash");

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("model", "gemini-3.5-flash");
        Map<String, Object> results = new LinkedHashMap<>();
        for (String name : SAMPLES) {
            results.put(name, enricher.enrich(name));
        }
        preview.put("enrichments", results);

        Path out = locate("docs/test_reports/workout_logs").resolve("enrichment_preview.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), preview);
        System.out.println("Wrote enrichment preview to " + out.toAbsolutePath());
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
