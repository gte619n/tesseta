package com.gte619n.healthfitness.integrations.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.config.JsonSupport;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseFramePlanner;
import com.gte619n.healthfitness.core.exercise.ExerciseReference;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Derives the reviewable frame plan ({@code demoPlan}) for an exercise with
 * {@code gemini-3.5-flash} (IMPL-19) — the approved general-work text model. The
 * planner reads the exercise's structured metadata plus, when an
 * {@link ExerciseReference} with a URL is present, the fetched readable text of
 * that public-library page, and emits the distinct teaching positions: 1 for a
 * hold, 2 for a standard lift, 3–5 for a skill/flow movement. Output is clamped
 * to 1–5 frames server-side, keys are sanitized to unique slugs, and
 * {@code order} is the array index.
 *
 * <p>Mirrors {@link GeminiExerciseMetadataEnricher}: shares the single
 * google-genai {@link Client} bean (present only when {@code GEMINI_API_KEY} is
 * set). The bean is injected as {@link Optional} so the planner exists even when
 * AI is unavailable; {@link #plan} then throws a clear
 * {@link IllegalStateException}, mirroring how the media service handles the
 * disabled state.
 */
@Component
public class GeminiExerciseFramePlanner implements ExerciseFramePlanner {

    private static final Logger log = LoggerFactory.getLogger(GeminiExerciseFramePlanner.class);

    private static final int MAX_FRAMES = 5;
    private static final int MIN_FRAMES = 1;
    private static final int REFERENCE_TEXT_CAP = 5000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(6);

    private static final String SYSTEM_PROMPT = """
        You are a strength & conditioning coach planning the demo stills a learner
        needs to understand ONE exercise. Decide how many distinct body positions
        a learner must SEE to perform it correctly, and describe each.

        Output ONLY a single JSON object — no prose, no markdown fences — shaped:
        {
          "frames": [
            {
              "key": "short stable slug, e.g. \\"start\\", \\"bottom\\", \\"lockout\\", \\"p1\\"",
              "label": "short UI label, e.g. \\"Bottom\\"",
              "caption": "one-line teaching cue, e.g. \\"Hips below parallel, chest tall\\"",
              "positionPrompt": "a concrete body/equipment position clause describing exactly what the body is doing at this instant, e.g. \\"at the absolute bottom of the squat, hips below parallel, chest tall, knees tracking over the toes\\""
            }
          ],
          "rationale": "one sentence on why this many frames"
        }

        Rules for the NUMBER of frames (return between 1 and 5):
        - Isometric HOLDS (planks, wall sits, hangs, carries) and steady-state
          CARDIO: exactly 1 frame (the held/working position).
        - Standard STRENGTH lifts (squat, press, row, curl, hinge): 2 frames —
          the start/setup and the working end (deepest or fully-contracted point).
        - Multi-position SKILL or FLOW movements (Turkish get-up, clean, snatch,
          a yoga sequence, a complex stretch with stages): 3 to 5 frames marking
          the meaningful checkpoints in order.

        The positionPrompt must be a concrete position clause analogous to a photo
        direction — describe the limbs, joints, and equipment at that instant. Do
        NOT restate the house photography style; that is added separately.
        Order the frames in the natural sequence of the movement.
        """;

    private final Optional<Client> client;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http;

    public GeminiExerciseFramePlanner(
        Optional<Client> client,
        @Value("${app.exercises.plan.model:gemini-3.5-flash}") String model
    ) {
        this.client = client;
        this.model = model;
        this.json = JsonSupport.LENIENT;
        this.http = HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public List<FrameSpec> plan(Exercise exercise, String promptOverride) {
        if (client.isEmpty()) {
            throw new IllegalStateException(
                "Exercise frame planner unavailable — GEMINI_API_KEY is not configured");
        }
        if (exercise == null) {
            throw new IllegalArgumentException("exercise is required");
        }
        try {
            List<Part> parts = new ArrayList<>();
            String instructions = (promptOverride != null && !promptOverride.isBlank())
                ? promptOverride : SYSTEM_PROMPT;
            parts.add(Part.fromText(instructions));
            parts.add(Part.fromText(describeExercise(exercise)));
            String refText = referenceText(exercise.reference());
            if (refText != null && !refText.isBlank()) {
                parts.add(Part.fromText(
                    "Reference description of this exercise from a public library "
                        + "(use for accuracy; ignore any unrelated page chrome):\n" + refText));
            }
            Content content = Content.fromParts(parts.toArray(new Part[0]));
            GenerateContentResponse response =
                client.get().models.generateContent(model, content, GenerateContentConfig.builder().build());
            String text = stripFences(response.text());
            if (text == null || !text.startsWith("{")) {
                log.warn("Planner returned non-JSON for {}: {}", exercise.exerciseId(), preview(text));
                throw new IllegalStateException("Frame planner returned no usable plan");
            }
            List<FrameSpec> frames = parseFrames(json.readTree(text));
            if (frames.isEmpty()) {
                throw new IllegalStateException("Frame planner produced zero frames");
            }
            return frames;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Frame planning failed for {}: {}", exercise.exerciseId(), e.getMessage(), e);
            throw new IllegalStateException("Frame planning failed: " + e.getMessage(), e);
        }
    }

    // ---- prompt assembly ----

    private static String describeExercise(Exercise e) {
        StringBuilder sb = new StringBuilder("Exercise to plan:\n");
        sb.append("- name: ").append(e.name() == null ? "(unknown)" : e.name()).append('\n');
        if (e.movementPattern() != null) {
            sb.append("- movementPattern: ").append(e.movementPattern().name()).append('\n');
        }
        if (e.mechanic() != null) {
            sb.append("- mechanic: ").append(e.mechanic().name()).append('\n');
        }
        if (e.laterality() != null) {
            sb.append("- laterality: ").append(e.laterality().name()).append('\n');
        }
        sb.append("- isTimed: ").append(e.isTimed())
            .append(" (true = a hold/cardio measured by duration)\n");
        if (e.description() != null && !e.description().isBlank()) {
            sb.append("- description: ").append(e.description()).append('\n');
        }
        if (e.formCues() != null && !e.formCues().isEmpty()) {
            sb.append("- formCues: ").append(String.join("; ", e.formCues())).append('\n');
        }
        return sb.toString();
    }

    /** Transient, best-effort fetch of the reference page as readable text. */
    private String referenceText(ExerciseReference ref) {
        if (ref == null || ref.url() == null || ref.url().isBlank()) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ref.url()))
                .timeout(FETCH_TIMEOUT)
                .header("User-Agent",
                    "Mozilla/5.0 (compatible; TessetaExerciseBot/1.0; +https://tesseta.app)")
                .header("Accept", "text/html,*/*")
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            return trimToCap(htmlToText(resp.body()));
        } catch (Exception e) {
            log.debug("Reference text fetch failed for {}: {}", ref.url(), e.getMessage());
            return null;
        }
    }

    /** Crude HTML→text: drop script/style, strip tags, collapse whitespace. */
    static String htmlToText(String html) {
        if (html == null) {
            return null;
        }
        String s = html
            .replaceAll("(?is)<script.*?</script>", " ")
            .replaceAll("(?is)<style.*?</style>", " ")
            .replaceAll("(?is)<!--.*?-->", " ")
            .replaceAll("(?is)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'")
            .replace("&quot;", "\"");
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String trimToCap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= REFERENCE_TEXT_CAP ? text : text.substring(0, REFERENCE_TEXT_CAP);
    }

    // ---- response parsing ----

    private List<FrameSpec> parseFrames(JsonNode root) {
        JsonNode arr = root.get("frames");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return List.of();
        }
        List<FrameSpec> out = new ArrayList<>();
        Set<String> usedKeys = new LinkedHashSet<>();
        int count = 0;
        for (JsonNode f : arr) {
            if (count >= MAX_FRAMES) {
                break;
            }
            String label = text(f, "label");
            String caption = text(f, "caption");
            String positionPrompt = text(f, "positionPrompt");
            if (positionPrompt == null || positionPrompt.isBlank()) {
                // Without a position clause the frame can't drive generation; skip.
                continue;
            }
            String key = uniqueKey(text(f, "key"), label, count, usedKeys);
            out.add(new FrameSpec(
                key,
                count,
                label == null || label.isBlank() ? defaultLabel(count) : label.trim(),
                caption == null ? "" : caption.trim(),
                positionPrompt.trim()));
            count++;
        }
        // Clamp lower bound: if everything got skipped we return empty (caller fails);
        // MIN_FRAMES is implicitly satisfied by the non-empty check.
        return out.size() >= MIN_FRAMES ? out : List.of();
    }

    private static String uniqueKey(String raw, String label, int index, Set<String> used) {
        String base = slug(raw);
        if (base.isEmpty()) {
            base = slug(label);
        }
        if (base.isEmpty()) {
            base = "p" + (index + 1);
        }
        String key = base;
        int n = 2;
        while (!used.add(key)) {
            key = base + "-" + n++;
        }
        return key;
    }

    private static String slug(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
    }

    private static String defaultLabel(int index) {
        return "Frame " + (index + 1);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static String stripFences(String text) {
        if (text == null) {
            return null;
        }
        text = text.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }

    private static String preview(String t) {
        if (t == null) {
            return "null";
        }
        return t.length() > 200 ? t.substring(0, 200) : t;
    }
}
