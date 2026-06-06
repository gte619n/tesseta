package com.gte619n.healthfitness.integrations.workoutprogram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.config.JsonSupport;
import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutBlockSplitter.Section;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Best-effort classifier that decides, for the mobility/stretch movements the
 * deterministic rule can't place, whether each is a <b>warm-up</b> or a
 * <b>cool-down</b> (or actually a working set → MAIN). Used by the one-time
 * import block-split job; the logged order is not warm-up→main→cool-down, so a
 * positional rule can't recover this — hence {@code gemini-3.5-flash}.
 *
 * <p>Classifies per unique exercise (not per session): cheap, reviewable, and
 * deterministic to apply (same exercise → same section in every session). On any
 * failure it returns no opinion for the affected items, leaving the caller's
 * default in place.
 *
 * <p>Gated on {@code app.workouts.split.gemini-enabled} so contexts without an
 * API key / the live {@link Client} bean don't construct it.
 */
@Component
@ConditionalOnProperty(name = "app.workouts.split.gemini-enabled", havingValue = "true", matchIfMissing = true)
public class GeminiWorkoutBlockClassifier {

    private static final Logger log = LoggerFactory.getLogger(GeminiWorkoutBlockClassifier.class);

    private static final int BATCH = 80;

    private static final String SYSTEM_PROMPT = """
        You are a strength & conditioning assistant. You are given a JSON array of
        exercises, each {"id","name","suitable"} where suitable is the list of
        block types the movement fits (WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY,
        CORE, COOLDOWN, STRETCH).

        For EACH exercise decide which section of a workout it belongs to:
        - "WARMUP": dynamic mobility / activation done to prepare for training
          (e.g. leg swings, arm circles, dynamic or banded movements, light cardio
          to start).
        - "COOLDOWN": static stretches / relaxation / recovery typically done at
          the end (e.g. pigeon pose, couch stretch, static holds, "recover").
        - "MAIN": actual working sets — strength, conditioning, core, or cardio
          performed as the workout itself.

        Output ONLY a single JSON object mapping each id to "WARMUP", "COOLDOWN",
        or "MAIN". No prose, no markdown fences.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json = JsonSupport.LENIENT;

    public GeminiWorkoutBlockClassifier(
        Client client,
        @Value("${app.workouts.split.model:gemini-3.5-flash}") String model
    ) {
        this.client = client;
        this.model = model;
    }

    public record ExerciseToClassify(String exerciseId, String name, List<BlockType> suitableBlockTypes) {}

    /** Classify each input exercise into a {@link Section}; unresolved ids are simply absent. */
    public Map<String, Section> classify(List<ExerciseToClassify> exercises) {
        Map<String, Section> out = new LinkedHashMap<>();
        if (exercises == null || exercises.isEmpty()) return out;
        for (int start = 0; start < exercises.size(); start += BATCH) {
            List<ExerciseToClassify> batch = exercises.subList(start, Math.min(start + BATCH, exercises.size()));
            try {
                classifyBatch(batch, out);
            } catch (Exception e) {
                log.warn("Block classify batch [{}..{}) failed: {}", start, start + batch.size(), e.getMessage());
            }
        }
        return out;
    }

    private void classifyBatch(List<ExerciseToClassify> batch, Map<String, Section> out) throws Exception {
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < batch.size(); i++) {
            ExerciseToClassify e = batch.get(i);
            if (i > 0) arr.append(',');
            arr.append("{\"id\":").append(json.writeValueAsString(e.exerciseId()))
                .append(",\"name\":").append(json.writeValueAsString(e.name() == null ? "" : e.name()))
                .append(",\"suitable\":").append(json.writeValueAsString(
                    e.suitableBlockTypes() == null ? List.of() : e.suitableBlockTypes().stream().map(Enum::name).toList()))
                .append('}');
        }
        arr.append(']');

        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText(SYSTEM_PROMPT));
        parts.add(Part.fromText("Exercises:\n" + arr));
        Content content = Content.fromParts(parts.toArray(new Part[0]));

        GenerateContentResponse response =
            client.models.generateContent(model, content, GenerateContentConfig.builder().build());
        String text = stripFences(response.text());
        if (text == null || !text.startsWith("{")) {
            log.warn("Block classify returned non-JSON: {}", preview(text));
            return;
        }
        JsonNode node = json.readTree(text);
        node.fields().forEachRemaining(entry -> {
            Section s = parseSection(entry.getValue().asText(null));
            if (s != null) out.put(entry.getKey(), s);
        });
    }

    private static Section parseSection(String raw) {
        if (raw == null) return null;
        try {
            return Section.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stripFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl >= 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String preview(String s) {
        if (s == null) return "null";
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
