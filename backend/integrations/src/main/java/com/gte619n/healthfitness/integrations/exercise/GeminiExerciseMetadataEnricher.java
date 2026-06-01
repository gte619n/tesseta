package com.gte619n.healthfitness.integrations.exercise;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Enriches a name-only exercise into structured catalog metadata with
 * {@code gemini-3.5-flash} (the approved general-work model). Pure structured
 * extraction — no grounding. Used by the IMPL-15 workout-history import to make
 * the 352 seeded movements usable (gym availability + image prompts depend on
 * the metadata). Mirrors {@code EquipmentParserService}.
 *
 * <p>Gated on {@code app.exercises.enrich-enabled} so tests (which install a
 * deterministic fake) and contexts without an API key don't construct the live
 * client.
 */
@Component
@ConditionalOnProperty(name = "app.exercises.enrich-enabled", havingValue = "true", matchIfMissing = true)
public class GeminiExerciseMetadataEnricher implements ExerciseMetadataEnricher {

    private static final Logger log = LoggerFactory.getLogger(GeminiExerciseMetadataEnricher.class);

    private static final String SYSTEM_PROMPT = """
        You are a strength & conditioning catalog assistant. Given ONE exercise
        name, return a single JSON object describing it. Output ONLY the JSON
        object — no prose, no markdown fences.

        Fields (use exactly these keys):
        - movementPattern: one of SQUAT, HINGE, LUNGE, PUSH_HORIZONTAL,
          PUSH_VERTICAL, PULL_HORIZONTAL, PULL_VERTICAL, CARRY, CORE, CARDIO,
          MOBILITY, STRETCH, OTHER
        - primaryMuscles: array of lowercase muscle names (e.g. ["quadriceps","glutes"])
        - secondaryMuscles: array of lowercase muscle names (may be empty)
        - laterality: BILATERAL or UNILATERAL
        - mechanic: COMPOUND or ISOLATION
        - description: one concise sentence describing the movement
        - formCues: array of 2-4 short imperative coaching cues
        - suitableBlockTypes: array from WARMUP, MOBILITY, CARDIO, MAIN,
          ACCESSORY, CORE, COOLDOWN, STRETCH
        - defaultRepRange: object {"min": int, "max": int}, or null for timed/cardio
        - isTimed: true if measured by duration/holds (planks, carries, cardio,
          "Recover"/rest), false if measured by reps
        - equipmentNameGroups: array of any-of groups, each an array of generic
          equipment names that could satisfy it (e.g. [["Dumbbells"]] or
          [["Barbell"],["Squat Rack","Power Rack"]]). Use [] for bodyweight.
          Use generic names (no brands).

        If the name is a rest/recovery placeholder (e.g. "Recover"), use
        movementPattern OTHER, isTimed true, empty muscles and equipment.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json;

    public GeminiExerciseMetadataEnricher(
        @Value("${app.exercises.enrich-api-key:${app.exercises.gemini-api-key:${GEMINI_API_KEY:}}}") String apiKey,
        @Value("${app.exercises.enrich-model:gemini-3.5-flash}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required for exercise metadata enrichment");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public Enrichment enrich(String exerciseName) {
        if (exerciseName == null || exerciseName.isBlank()) {
            return ExerciseMetadataEnricher.empty(exerciseName);
        }
        try {
            Content content = Content.fromParts(
                Part.fromText(SYSTEM_PROMPT),
                Part.fromText("Exercise name: " + exerciseName));
            GenerateContentResponse response =
                client.models.generateContent(model, content, GenerateContentConfig.builder().build());
            String text = stripFences(response.text());
            if (text == null || !text.startsWith("{")) {
                log.warn("Enricher returned non-JSON for '{}': {}", exerciseName, preview(text));
                return ExerciseMetadataEnricher.empty(exerciseName);
            }
            return parse(json.readTree(text));
        } catch (Exception e) {
            log.warn("Enrichment failed for '{}': {}", exerciseName, e.getMessage());
            return ExerciseMetadataEnricher.empty(exerciseName);
        }
    }

    private Enrichment parse(JsonNode n) {
        RepRange rr = null;
        JsonNode rrn = n.get("defaultRepRange");
        if (rrn != null && rrn.isObject() && rrn.hasNonNull("min") && rrn.hasNonNull("max")) {
            rr = new RepRange(rrn.get("min").asInt(), rrn.get("max").asInt());
        }
        return new Enrichment(
            enumOrDefault(text(n, "movementPattern"), MovementPattern.class, MovementPattern.OTHER),
            strings(n.get("primaryMuscles")),
            strings(n.get("secondaryMuscles")),
            enumOrDefault(text(n, "laterality"), Laterality.class, Laterality.BILATERAL),
            enumOrDefault(text(n, "mechanic"), Mechanic.class, Mechanic.COMPOUND),
            text(n, "description"),
            strings(n.get("formCues")),
            blockTypes(n.get("suitableBlockTypes")),
            rr,
            n.path("isTimed").asBoolean(false),
            groups(n.get("equipmentNameGroups")));
    }

    private static List<BlockType> blockTypes(JsonNode arr) {
        List<BlockType> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                BlockType bt = enumOrDefault(e.asText(null), BlockType.class, null);
                if (bt != null) out.add(bt);
            }
        }
        return out.isEmpty() ? List.of(BlockType.MAIN) : out;
    }

    private static List<List<String>> groups(JsonNode arr) {
        List<List<String>> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode g : arr) {
                List<String> names = strings(g);
                if (!names.isEmpty()) out.add(names);
            }
        }
        return out;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode e : arr) {
                String s = e.asText(null);
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static <E extends Enum<E>> E enumOrDefault(String name, Class<E> type, E def) {
        if (name == null) return def;
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static String stripFences(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.startsWith("```json")) text = text.substring(7);
        else if (text.startsWith("```")) text = text.substring(3);
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
        return text.trim();
    }

    private static String preview(String t) {
        if (t == null) return "null";
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
