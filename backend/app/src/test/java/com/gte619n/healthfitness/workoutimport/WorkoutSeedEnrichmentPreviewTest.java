package com.gte619n.healthfitness.workoutimport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher.Enrichment;
import com.gte619n.healthfitness.integrations.exercise.GeminiExerciseMetadataEnricher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** The real production equipment catalog vocabulary (29 names). */
    private static final List<String> CATALOG = List.of(
        "Abdominal / Back Extension Bench", "Adjustable Weight Bench", "Assisted Dip / Chin-Up Machine",
        "Bosu Ball", "Chest Press Machine", "Dual Adjustable Pulley Cable Machine", "Dumbbells",
        "EZ Curl Barbells", "Echo Air Bike", "Elliptical Machine", "Exercise Mat",
        "Functional Trainer Cable Machine", "Kettlebells", "Lat Pulldown Machine", "Leg Extension Machine",
        "Leg Press Machine", "Medicine Balls", "Pec Deck / Rear Delt Machine", "Recumbent Bike",
        "Rowing Machine", "Seated Leg Curl Machine", "Seated Row Machine", "Smith Machine",
        "Stability Ball", "Stationary Bike", "Treadmill", "Triceps Press Machine", "Vibration Platform");

    @Test
    void previewRealEnrichment() throws Exception {
        ExerciseMetadataEnricher enricher = new GeminiExerciseMetadataEnricher(
            System.getenv("GEMINI_API_KEY"), "gemini-3.5-flash");

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("model", "gemini-3.5-flash");
        Map<String, Enrichment> results = new LinkedHashMap<>();
        for (String name : SAMPLES) {
            results.put(name, enricher.enrich(name, CATALOG));
        }
        preview.put("enrichments", results);

        Path out = locate("docs/test_reports/workout_logs").resolve("enrichment_preview.json");
        Files.createDirectories(out.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out.toFile(), preview);
        System.out.println("Wrote enrichment preview to " + out.toAbsolutePath());

        // Equipment-vocabulary constraint: every emitted equipment name must be a
        // member of the real production catalog (exact string match).
        Set<String> emittedEquipment = new LinkedHashSet<>();
        for (Map.Entry<String, Enrichment> entry : results.entrySet()) {
            String sampleName = entry.getKey();
            for (List<String> group : entry.getValue().equipmentNameGroups()) {
                for (String equipName : group) {
                    emittedEquipment.add(equipName);
                    assertThat(CATALOG)
                        .as("model emitted in-vocabulary equipment for %s", sampleName)
                        .contains(equipName);
                }
            }
        }
        System.out.println("Emitted equipment names: " + emittedEquipment);
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
