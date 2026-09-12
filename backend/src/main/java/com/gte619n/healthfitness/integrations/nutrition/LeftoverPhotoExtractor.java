package com.gte619n.healthfitness.integrations.nutrition;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
import com.google.genai.types.Type;
import com.gte619n.healthfitness.core.nutrition.LeftoverAnalyzer;
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
 * Gemini-backed {@link LeftoverAnalyzer} (IMPL-LEFTOVER-01, spec D1): compares the
 * ORIGINAL meal photo with a new LEFTOVER photo and estimates, per served item,
 * how much is still on the plate, via {@code gemini-3.8-flash} tool calling
 * ({@code estimate_leftovers}). Mirrors {@link MealPhotoExtractor}'s multimodal
 * tool-calling style but takes TWO images.
 *
 * <p>Uses the shared flash model/key ({@code app.nutrition.gemini-model} /
 * default {@code GEMINI_MODEL}) and is gated by {@code app.nutrition.capture.enabled}
 * so test contexts skip the live bean, matching {@link MealPhotoExtractor}.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class LeftoverPhotoExtractor implements LeftoverAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(LeftoverPhotoExtractor.class);

    static final String TOOL_NAME = "estimate_leftovers";

    private static final String PROMPT = """
        You are a nutrition vision assistant estimating LEFTOVERS. You are given:
          1. The ORIGINAL photo of a meal as it was served.
          2. A NEW photo of the SAME meal's LEFTOVERS — what is still on the plate
             after the person ate.
          3. As text, the meal's served components and their as-served weight in grams.

        For EACH served component, estimate how many GRAMS of it are STILL LEFT in
        the leftover photo (0 if it was completely eaten; the full served weight if
        it looks untouched). Compare the two photos to judge the fraction remaining;
        use the served grams as the scale reference.

        Rules:
        - remainingGrams must be between 0 and the item's served grams. Never exceed
          the served amount and never go negative.
        - set matched = true only for components you can actually locate/relate in
          the leftover photo; set matched = false if you cannot tell (do NOT guess a
          remaining amount — leave remainingGrams null when matched is false).
        - If the leftover photo is unreadable, empty, or clearly not this meal,
          return an empty items array and a low overallConfidence.
        - overallConfidence in [0,1] is how confident you are about the whole estimate.
        - Call the estimate_leftovers tool; do not reply in prose.
        """;

    private final Client client;
    private final String model;
    private final Tool tool;

    public LeftoverPhotoExtractor(
        Client client,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.8-flash}}") String model
    ) {
        this.client = client;
        this.model = model;
        this.tool = Tool.builder()
            .functionDeclarations(List.of(estimateLeftoversTool()))
            .build();
    }

    @Override
    public LeftoverEstimate estimate(
        ServedMeal served,
        byte[] originalPhoto,
        String originalMime,
        byte[] leftoverPhoto,
        String leftoverMime) {
        if (leftoverPhoto == null || leftoverPhoto.length == 0) {
            throw new NutritionExtractionException("leftover photo is empty");
        }

        List<Part> parts = new ArrayList<>();
        parts.add(Part.fromText(PROMPT));
        parts.add(Part.fromText(renderServedMeal(served)));
        if (originalPhoto != null && originalPhoto.length > 0) {
            parts.add(Part.fromText("Original meal photo (as served):"));
            parts.add(Part.fromBytes(originalPhoto, mimeOr(originalMime)));
        }
        parts.add(Part.fromText("Leftover photo (what is left on the plate now):"));
        parts.add(Part.fromBytes(leftoverPhoto, mimeOr(leftoverMime)));

        Content content = Content.fromParts(parts.toArray(new Part[0]));
        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(tool))
            .build();

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (RuntimeException e) {
            log.warn("Leftover estimation call failed: {}", e.getMessage());
            throw new NutritionExtractionException("leftover estimation failed", e);
        }

        Map<String, Object> args = toolArgs(response);
        if (args == null) {
            throw new NutritionExtractionException(
                "Gemini did not return an estimate_leftovers tool call");
        }
        return toEstimate(args);
    }

    private static String mimeOr(String mime) {
        return (mime == null || mime.isBlank()) ? "image/jpeg" : mime;
    }

    private static String renderServedMeal(ServedMeal served) {
        StringBuilder sb = new StringBuilder("Served meal:\n");
        if (served != null) {
            sb.append("mealName: ").append(served.mealName() == null ? "" : served.mealName())
                .append('\n');
            sb.append("components:\n");
            if (served.items() != null) {
                for (ServedMeal.Item it : served.items()) {
                    if (it == null) continue;
                    sb.append("  - name: ").append(it.name() == null ? "" : it.name());
                    if (it.servedGrams() != null) {
                        sb.append(", servedGrams: ").append(Math.round(it.servedGrams()));
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> toolArgs(GenerateContentResponse response) {
        List<FunctionCall> calls = response.functionCalls();
        if (calls == null) {
            return null;
        }
        for (FunctionCall call : calls) {
            if (TOOL_NAME.equals(call.name().orElse(null))) {
                return call.args().orElse(Map.of());
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static LeftoverEstimate toEstimate(Map<String, Object> args) {
        if (args == null) {
            return new LeftoverEstimate(List.of(), 0.0);
        }
        double overall = dbl(args.get("overallConfidence")) != null
            ? dbl(args.get("overallConfidence")) : 0.0;
        List<LeftoverEstimate.ItemEstimate> items = new ArrayList<>();
        Object raw = args.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> item = (Map<String, Object>) m;
                items.add(new LeftoverEstimate.ItemEstimate(
                    str(item.get("name")),
                    dbl(item.get("remainingGrams")),
                    bool(item.get("matched")),
                    dbl(item.get("confidence")) != null ? dbl(item.get("confidence")) : 0.0));
            }
        }
        return new LeftoverEstimate(items, overall);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) return b;
        return o != null && Boolean.parseBoolean(o.toString());
    }

    private static FunctionDeclaration estimateLeftoversTool() {
        Schema item = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "name", Schema.builder().type(Type.Known.STRING)
                    .description("The served component this refers to, matching a given name.").build(),
                "remainingGrams", Schema.builder().type(Type.Known.NUMBER)
                    .description("Grams of this component still on the plate (0..servedGrams). "
                        + "Leave unset when matched is false.").build(),
                "matched", Schema.builder().type(Type.Known.BOOLEAN)
                    .description("True only if you can locate/relate this component in the "
                        + "leftover photo.").build(),
                "confidence", Schema.builder().type(Type.Known.NUMBER)
                    .description("Per-item confidence in [0,1].").build()
            ))
            .required("name", "matched")
            .build();

        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "overallConfidence", Schema.builder().type(Type.Known.NUMBER)
                    .description("Overall confidence in the whole leftover estimate, in [0,1].").build(),
                "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()
            ))
            .required("overallConfidence", "items")
            .build();

        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Return the estimated remaining (uneaten) grams of each served component.")
            .parameters(params)
            .build();
    }

    private static Map<String, Schema> orderedMap(Object... kv) {
        Map<String, Schema> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put((String) kv[i], (Schema) kv[i + 1]);
        }
        return map;
    }
}
