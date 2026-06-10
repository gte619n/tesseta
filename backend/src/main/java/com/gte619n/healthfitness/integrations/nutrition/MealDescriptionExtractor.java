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
import com.gte619n.healthfitness.core.nutrition.MealDescriptionAnalyzer;
import com.gte619n.healthfitness.core.nutrition.MealPhotoAnalyzer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed {@link MealDescriptionAnalyzer}: the text-input sibling of
 * {@link MealPhotoExtractor}. Itemizes a free-text meal description into its food
 * components via {@code gemini-3.5-flash} tool calling ({@code extract_meal_items}),
 * and picks the best existing saved-meal match via a second tool
 * ({@code choose_meal_match}).
 *
 * <p>Uses the SHARED flash model/key ({@code app.nutrition.gemini-model} /
 * {@code GEMINI_MODEL}) — NOT the Goals Pro model. Per ADR-0005 + root CLAUDE.md,
 * extraction stays on flash. Gated by {@code app.nutrition.capture.enabled}
 * (default true) so test contexts skip the live bean, matching
 * {@link MealPhotoExtractor}.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class MealDescriptionExtractor implements MealDescriptionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(MealDescriptionExtractor.class);

    static final String EXTRACT_TOOL = "extract_meal_items";
    static final String MATCH_TOOL = "choose_meal_match";

    private static final String EXTRACT_PROMPT = """
        You are a nutrition assistant. You are given a short free-text DESCRIPTION
        of a meal a user ate (e.g. "grilled chicken with rice and broccoli", or
        "a chocolate protein shake"). First decide what kind of meal it is:

        A) A SINGLE PACKAGED PRODUCT — one branded/packaged good (a shake, a tub of
           yogurt, an energy bar). Treat it as ONE product:
           - set isPackagedProduct = true,
           - return exactly ONE item whose name is the product itself,
           - mealName = that same product name.

        B) A PREPARED MEAL — a plate or bowl with distinct components. Treat it as
           a multi-ingredient meal:
           - set isPackagedProduct = false,
           - return one item per DISTINCT food component (e.g. "grilled chicken
             breast", "white rice", "steamed broccoli"),
           - mealName = a SHORT, natural dish name, e.g. "Chicken, rice and
             broccoli" — NOT a comma-joined list of every ingredient.

        For EACH item:
          - estimate its portion weight in GRAMS as typically served (use the
            quantities in the description when given, otherwise a realistic
            default portion),
          - give its macros PER 100 GRAMS of that food (not per portion):
            caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams,
          - give a confidence in [0,1] for how sure you are of the identification.

        Rules:
        - Macros are ALWAYS per 100 g of the food itself, independent of portion.
        - Use realistic reference values for common foods.
        - If the text names no identifiable food, return an empty items array.
        - Always provide mealName and isPackagedProduct.
        - Call the extract_meal_items tool; do not reply in prose.
        """;

    private static final String MATCH_PROMPT = """
        A user described a meal they ate. Below is their description and a numbered
        list of saved meals already in the catalog. Decide whether ANY saved meal
        is the SAME dish the user is describing (same core foods — minor wording or
        portion differences are fine). If one is a genuine match, return its id in
        matchedMealId. If none is truly the same dish, return matchedMealId = null
        (an empty string) so a new meal is created instead. Prefer a NEW meal over
        a loose/approximate match. Call the choose_meal_match tool; do not reply in
        prose.
        """;

    private final Client client;
    private final String model;
    private final Tool extractTool;
    private final Tool matchTool;

    public MealDescriptionExtractor(
        Client client,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.5-flash}}") String model
    ) {
        this.client = client;
        this.model = model;
        this.extractTool = Tool.builder()
            .functionDeclarations(List.of(extractMealItemsTool()))
            .build();
        this.matchTool = Tool.builder()
            .functionDeclarations(List.of(chooseMealMatchTool()))
            .build();
    }

    @Override
    public MealPhotoAnalyzer.MealAnalysis analyze(String description) {
        if (description == null || description.isBlank()) {
            throw new NutritionExtractionException("meal description is empty");
        }
        Content content = Content.fromParts(
            Part.fromText(EXTRACT_PROMPT),
            Part.fromText("MEAL DESCRIPTION:\n" + description.strip())
        );
        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(extractTool))
            .build();

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (RuntimeException e) {
            log.warn("Meal description extraction call failed: {}", e.getMessage());
            throw new NutritionExtractionException("meal description extraction failed", e);
        }
        Map<String, Object> args = toolArgs(response, EXTRACT_TOOL);
        if (args == null) {
            throw new NutritionExtractionException(
                "Gemini did not return an extract_meal_items tool call");
        }
        return toAnalysis(args);
    }

    @Override
    public Optional<String> matchMeal(String description, List<MealCandidate> candidates) {
        if (description == null || description.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MEAL DESCRIPTION:\n").append(description.strip()).append("\n\nSAVED MEALS:\n");
        for (MealCandidate c : candidates) {
            sb.append("- id=").append(c.mealId()).append(" : ").append(c.name()).append('\n');
        }
        Content content = Content.fromParts(Part.fromText(MATCH_PROMPT), Part.fromText(sb.toString()));
        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(matchTool))
            .build();
        try {
            GenerateContentResponse response = client.models.generateContent(model, content, config);
            Map<String, Object> args = toolArgs(response, MATCH_TOOL);
            if (args == null) {
                return Optional.empty();
            }
            String id = str(args.get("matchedMealId"));
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            // Only honour an id the model was actually offered.
            boolean known = candidates.stream().anyMatch(c -> id.equals(c.mealId()));
            return known ? Optional.of(id) : Optional.empty();
        } catch (RuntimeException e) {
            // Matching is best-effort — a failure just means "create a new meal".
            log.warn("Meal match call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Map<String, Object> toolArgs(GenerateContentResponse response, String toolName) {
        List<FunctionCall> calls = response.functionCalls();
        if (calls == null) {
            return null;
        }
        for (FunctionCall call : calls) {
            if (toolName.equals(call.name().orElse(null))) {
                return call.args().orElse(Map.of());
            }
        }
        return null;
    }

    /** Build the analysis (name + packaged flag + items) from tool args. */
    static MealPhotoAnalyzer.MealAnalysis toAnalysis(Map<String, Object> args) {
        if (args == null) {
            return new MealPhotoAnalyzer.MealAnalysis(null, false, List.of());
        }
        String mealName = str(args.get("mealName"));
        boolean packaged = bool(args.get("isPackagedProduct"));
        return new MealPhotoAnalyzer.MealAnalysis(
            (mealName != null && !mealName.isBlank()) ? mealName : null,
            packaged,
            toItems(args));
    }

    @SuppressWarnings("unchecked")
    static List<MealPhotoAnalyzer.MealItem> toItems(Map<String, Object> args) {
        List<MealPhotoAnalyzer.MealItem> result = new ArrayList<>();
        if (args == null) return result;
        Object raw = args.get("items");
        if (!(raw instanceof List<?> list)) return result;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> item = (Map<String, Object>) m;
            Macros macros = macros(item.get("macrosPer100g"));
            result.add(new MealPhotoAnalyzer.MealItem(
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
        // The model's kcal estimate can drift from its own macro estimates;
        // derive calories so they are always consistent (4/4/9).
        return new Macros(
            dbl(m.get("caloriesKcal")),
            dbl(m.get("proteinGrams")),
            dbl(m.get("carbsGrams")),
            dbl(m.get("fatGrams")),
            dbl(m.get("fiberGrams")),
            dbl(m.get("sugarGrams"))
        ).withDerivedCalories();
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

    // ---- tool schemas ----

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
                    .description("Short natural dish/product name, e.g. 'Chicken, rice and "
                        + "broccoli' or 'Chocolate protein shake' — not a comma-joined list.").build(),
                "isPackagedProduct", Schema.builder().type(Type.Known.BOOLEAN)
                    .description("True if the description is a single packaged product rather "
                        + "than a prepared multi-ingredient meal.").build(),
                "items", Schema.builder().type(Type.Known.ARRAY).items(item).build()
            ))
            .required("mealName", "isPackagedProduct", "items")
            .build();

        return FunctionDeclaration.builder()
            .name(EXTRACT_TOOL)
            .description("Return the itemized food components for the described meal.")
            .parameters(params)
            .build();
    }

    private static FunctionDeclaration chooseMealMatchTool() {
        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "matchedMealId", Schema.builder().type(Type.Known.STRING)
                    .description("The id of the saved meal that is the SAME dish as the "
                        + "description, or an empty string if none genuinely matches.").build()
            ))
            .required("matchedMealId")
            .build();
        return FunctionDeclaration.builder()
            .name(MATCH_TOOL)
            .description("Pick the saved meal that matches the description, or none.")
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
