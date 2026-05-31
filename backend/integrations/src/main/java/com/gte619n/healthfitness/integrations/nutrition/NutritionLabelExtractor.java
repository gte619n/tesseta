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
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.NutritionLabelAnalyzer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed {@link NutritionLabelAnalyzer}: OCRs a packaged nutrition-label
 * photo into a structured food via {@code gemini-3.5-flash} tool calling
 * ({@code extract_nutrition_label}). The model reports macros PER SERVING (as
 * the panel prints them); this extractor normalizes to PER 100 G via
 * {@link #toPer100g(Macros, Double)} so the result maps directly onto
 * {@code CatalogFood.macrosPer100g}.
 *
 * <p>Uses the SHARED flash model/key, NOT the Goals Pro model (ADR-0005 + root
 * CLAUDE.md). Gated by {@code app.nutrition.capture.enabled} so test contexts
 * skip the live bean.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class NutritionLabelExtractor implements NutritionLabelAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(NutritionLabelExtractor.class);

    static final String TOOL_NAME = "extract_nutrition_label";

    private static final String SYSTEM_PROMPT = """
        You are reading a packaged-food NUTRITION FACTS label from ONE photo.
        Extract the panel exactly as printed:
          - productName and brand if visible on the package,
          - servingSizeGrams: the serving size in grams (convert mL≈g for liquids
            if grams are not printed; null if you cannot determine it),
          - servingsPerContainer if printed,
          - macrosPerServing: the values printed FOR ONE SERVING —
            caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams.

        Rules:
        - Report macros PER SERVING exactly as printed; do NOT convert to per-100 g.
        - If a nutrient is absent from the panel, return null for it.
        - Call the extract_nutrition_label tool; do not reply in prose.
        """;

    private final Client client;
    private final String model;
    private final Tool tool;

    public NutritionLabelExtractor(
        @Value("${app.nutrition.gemini-api-key:${GEMINI_API_KEY:}}") String apiKey,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.5-flash}}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY (app.nutrition.gemini-api-key) is required for nutrition label extraction");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.tool = Tool.builder()
            .functionDeclarations(List.of(extractLabelTool()))
            .build();
    }

    @Override
    public LabelExtraction analyze(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NutritionExtractionException("label photo is empty");
        }
        String contentType = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;

        Content content = Content.fromParts(
            Part.fromText(SYSTEM_PROMPT),
            Part.fromBytes(imageBytes, contentType)
        );
        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(tool))
            .build();

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (RuntimeException e) {
            log.warn("Nutrition label extraction call failed: {}", e.getMessage());
            throw new NutritionExtractionException("nutrition label extraction failed", e);
        }

        Map<String, Object> args = toolArgs(response);
        if (args == null) {
            throw new NutritionExtractionException(
                "Gemini did not return an extract_nutrition_label tool call");
        }
        return toLabel(args);
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
    static LabelExtraction toLabel(Map<String, Object> args) {
        if (args == null) {
            throw new NutritionExtractionException("empty label tool args");
        }
        Double servingGrams = dbl(args.get("servingSizeGrams"));
        Macros perServing = null;
        Object raw = args.get("macrosPerServing");
        if (raw instanceof Map<?, ?> mm) {
            Map<String, Object> m = (Map<String, Object>) mm;
            perServing = new Macros(
                dbl(m.get("caloriesKcal")),
                dbl(m.get("proteinGrams")),
                dbl(m.get("carbsGrams")),
                dbl(m.get("fatGrams")),
                dbl(m.get("fiberGrams")),
                dbl(m.get("sugarGrams"))
            );
        }
        return new LabelExtraction(
            str(args.get("productName")),
            str(args.get("brand")),
            servingGrams,
            dbl(args.get("servingsPerContainer")),
            toPer100g(perServing, servingGrams)
        );
    }

    /**
     * Normalize per-serving macros to per-100 g:
     * {@code per100g = perServing × 100 / servingGrams}. When the serving size is
     * missing or non-positive we cannot normalize, so the per-serving values are
     * returned unchanged (best effort) — the client can still review/edit them.
     */
    static Macros toPer100g(Macros perServing, Double servingGrams) {
        if (perServing == null) {
            return null;
        }
        if (servingGrams == null || servingGrams <= 0) {
            return perServing;
        }
        return perServing.scale(100.0 / servingGrams);
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

    // ---- tool schema ----

    private static FunctionDeclaration extractLabelTool() {
        Schema macros = Schema.builder()
            .type(Type.Known.OBJECT)
            .description("Macros PER SERVING, exactly as printed on the panel.")
            .properties(orderedMap(
                "caloriesKcal", Schema.builder().type(Type.Known.NUMBER).build(),
                "proteinGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "carbsGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fatGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fiberGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "sugarGrams", Schema.builder().type(Type.Known.NUMBER).build()
            ))
            .required("caloriesKcal")
            .build();

        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "productName", Schema.builder().type(Type.Known.STRING).build(),
                "brand", Schema.builder().type(Type.Known.STRING).build(),
                "servingSizeGrams", Schema.builder().type(Type.Known.NUMBER)
                    .description("Serving size in grams.").build(),
                "servingsPerContainer", Schema.builder().type(Type.Known.NUMBER).build(),
                "macrosPerServing", macros
            ))
            .required("macrosPerServing")
            .build();

        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Return the structured nutrition facts read from the label photo.")
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
