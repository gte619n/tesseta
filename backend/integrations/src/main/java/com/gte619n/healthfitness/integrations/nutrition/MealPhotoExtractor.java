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
import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer;
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
 * Gemini-backed {@link MealPhotoAnalyzer}: itemizes a full-meal photo into its
 * food components via {@code gemini-3.5-flash} tool calling
 * ({@code extract_meal_items}), mirroring {@code GeminiGoalChatClient}'s
 * tool-calling style and {@code DexaExtractor}'s multimodal image input.
 *
 * <p>Uses the SHARED flash model/key ({@code app.nutrition.gemini-model} /
 * {@code app.nutrition.gemini-api-key}, defaulting to the same {@code GEMINI_MODEL}
 * / {@code GEMINI_API_KEY} env vars DEXA and blood-test read) — NOT the Goals Pro
 * model. Per ADR-0005 + root CLAUDE.md, extraction stays on flash.
 *
 * <p>Gated by {@code app.nutrition.capture.enabled} (default true) so test
 * contexts skip the live bean (no API key required), matching DEXA/blood-test.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class MealPhotoExtractor implements MealPhotoAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MealPhotoExtractor.class);

    static final String TOOL_NAME = "extract_meal_items";

    private static final String SYSTEM_PROMPT = """
        You are a nutrition vision assistant. You are given ONE photo of food.
        First decide what kind of photo it is:

        A) A SINGLE PACKAGED PRODUCT — a branded, packaged good shown as one unit
           (a bottle, can, carton, tub, pouch, bar or wrapper), e.g. a protein
           shake, a tub of yogurt, an energy bar. Treat it as ONE product:
           - set isPackagedProduct = true,
           - return exactly ONE item whose name is the product itself (e.g.
             "Greek yogurt", "Chocolate protein shake") — NOT a list of its
             ingredients and NOT the brand/label text,
           - mealName = that same product name.

        B) A PREPARED MEAL — a plate or bowl of food with distinct components.
           Treat it as a multi-ingredient meal:
           - set isPackagedProduct = false,
           - return one item per DISTINCT food component (e.g. "grilled chicken
             breast", "white rice", "steamed broccoli"); do not merge a mixed
             plate into one item,
           - mealName = a SHORT, natural dish name of its main components, e.g.
             "Salmon and broccoli" or "Chicken burrito bowl" — NOT a comma-joined
             list of every ingredient.

        For EACH item:
          - estimate its portion weight in GRAMS as actually served/contained,
          - give its macros PER 100 GRAMS of that food (not per portion):
            caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams,
          - give a confidence in [0,1] for how sure you are of the identification.

        Rules:
        - Macros are ALWAYS per 100 g of the food itself, independent of portion.
        - Use realistic reference values for common foods.
        - If you cannot identify any food, return an empty items array.
        - Always provide mealName and isPackagedProduct.
        - Call the extract_meal_items tool; do not reply in prose.
        """;

    private final Client client;
    private final String model;
    private final Tool tool;

    public MealPhotoExtractor(
        Client client,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.5-flash}}") String model
    ) {
        this.client = client;
        this.model = model;
        this.tool = Tool.builder()
            .functionDeclarations(List.of(extractMealItemsTool()))
            .build();
    }

    @Override
    public List<MealItem> analyze(byte[] imageBytes, String mimeType) {
        return analyzeMeal(imageBytes, mimeType).items();
    }

    @Override
    public MealAnalysis analyzeMeal(byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new NutritionExtractionException("meal photo is empty");
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
            log.warn("Meal photo extraction call failed: {}", e.getMessage());
            throw new NutritionExtractionException("meal photo extraction failed", e);
        }

        Map<String, Object> args = toolArgs(response);
        if (args == null) {
            throw new NutritionExtractionException(
                "Gemini did not return an extract_meal_items tool call");
        }
        return toAnalysis(args);
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

    /** Build the full analysis (name + packaged flag + items) from tool args. */
    static MealAnalysis toAnalysis(Map<String, Object> args) {
        if (args == null) {
            return new MealAnalysis(null, false, List.of());
        }
        String mealName = str(args.get("mealName"));
        boolean packaged = bool(args.get("isPackagedProduct"));
        return new MealAnalysis(
            (mealName != null && !mealName.isBlank()) ? mealName : null,
            packaged,
            toItems(args));
    }

    @SuppressWarnings("unchecked")
    static List<MealItem> toItems(Map<String, Object> args) {
        List<MealItem> result = new ArrayList<>();
        if (args == null) return result;
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list)) return result;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            Macros macros = macros(item.get("macrosPer100g"));
            result.add(new MealItem(
                str(item.get("name")),
                dbl(item.get("estimatedPortionGrams")),
                macros,
                dbl(item.get("confidence"))
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Macros macros(Object raw) {
        if (!(raw instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m = (Map<String, Object>) mm;
        return new Macros(
            dbl(m.get("caloriesKcal")),
            dbl(m.get("proteinGrams")),
            dbl(m.get("carbsGrams")),
            dbl(m.get("fatGrams")),
            dbl(m.get("fiberGrams")),
            dbl(m.get("sugarGrams"))
        );
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

    // ---- tool schema ----

    private static FunctionDeclaration extractMealItemsTool() {
        Schema macros = Schema.builder()
            .type(Type.Known.OBJECT)
            .description("Macros per 100 g of this food.")
            .properties(orderedMap(
                "caloriesKcal", Schema.builder().type(Type.Known.NUMBER).build(),
                "proteinGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "carbsGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fatGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fiberGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "sugarGrams", Schema.builder().type(Type.Known.NUMBER).build()
            ))
            .required("caloriesKcal", "proteinGrams", "carbsGrams", "fatGrams")
            .build();

        Schema item = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "name", Schema.builder().type(Type.Known.STRING)
                    .description("Name of the food component, e.g. 'Grilled chicken breast'").build(),
                "estimatedPortionGrams", Schema.builder().type(Type.Known.NUMBER)
                    .description("Estimated weight of this component as served, in grams.").build(),
                "macrosPer100g", macros,
                "confidence", Schema.builder().type(Type.Known.NUMBER)
                    .description("Identification confidence in [0,1].").build()
            ))
            .required("name", "estimatedPortionGrams", "macrosPer100g")
            .build();

        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "mealName", Schema.builder().type(Type.Known.STRING)
                    .description("Short natural dish/product name, e.g. 'Salmon and broccoli' "
                        + "or 'Greek yogurt' — not a comma-joined ingredient list.").build(),
                "isPackagedProduct", Schema.builder().type(Type.Known.BOOLEAN)
                    .description("True if the photo is a single packaged product (bottle, tub, "
                        + "can, bar, wrapper) rather than a prepared multi-ingredient meal.").build(),
                "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()
            ))
            .required("mealName", "isPackagedProduct", "items")
            .build();

        return FunctionDeclaration.builder()
            .name(TOOL_NAME)
            .description("Return the itemized food components identified in the meal photo.")
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
