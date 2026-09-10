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
import com.gte619n.healthfitness.core.nutrition.DrinkAnalyzer;
import com.gte619n.healthfitness.core.nutrition.Macros;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed {@link DrinkAnalyzer} (IMPL-DRINK-01): estimates a drink's ABV%,
 * typical serving volume and per-serving mixer macros from its name via
 * {@code gemini-3.8-flash} tool calling ({@code extract_drink}). The model does NOT
 * do the alcohol arithmetic — it only estimates ABV%, volume and the mixer's
 * sugar/carb contribution; {@code DrinkMath} derives grams/standard-drinks/calories
 * (D10). Gated by {@code app.nutrition.capture.enabled} so test contexts skip the
 * live bean, matching {@link MealDescriptionExtractor}.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class DrinkExtractor implements DrinkAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(DrinkExtractor.class);

    static final String EXTRACT_TOOL = "extract_drink";

    private static final String EXTRACT_PROMPT = """
        You are a bartender's nutrition assistant. You are given the NAME of a
        single alcoholic drink (e.g. "Negroni", "vodka soda", "6oz glass of
        malbec", "pint of Guinness", "double gin and tonic"). Estimate:

        - name: a clean, capitalized display name for the drink.
        - abvPercent: the drink's approximate alcohol by volume, as a percent of
          the FINISHED drink as served (a gin & tonic is ~10-12%, a negroni
          ~24-28%, wine ~13%, beer ~5%, a neat 2oz whiskey ~40%). Account for
          mixers diluting spirits.
        - servingVolumeMl: the typical volume of ONE serving as described (a
          cocktail ~90-120 ml, a highball ~250-350 ml, a glass of wine ~150 ml, a
          pint ~470 ml, a shot ~44 ml). Honor explicit sizes in the name
          ("6oz" ≈ 177 ml, "double" ≈ 2× the spirit).
        - the per-serving macros from any MIXERS / sugar ONLY (do NOT include
          alcohol calories — those are computed separately): proteinGrams,
          carbsGrams, fatGrams, fiberGrams, sugarGrams for the whole serving. For
          a neat spirit or a soda-water mixer these are all ~0; for a tonic/juice/
          syrup drink, estimate the sugar and carbs from the mixer.

        Rules:
        - These are ALCOHOLIC drinks. If the name is clearly non-alcoholic, still
          estimate as asked but set abvPercent to your best low estimate.
        - Macros are for the WHOLE serving, not per 100 g.
        - Call the extract_drink tool; do not reply in prose.
        """;

    private final Client client;
    private final String model;
    private final Tool extractTool;

    public DrinkExtractor(
        Client client,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.8-flash}}") String model
    ) {
        this.client = client;
        this.model = model;
        this.extractTool = Tool.builder()
            .functionDeclarations(List.of(extractDrinkTool()))
            .build();
    }

    @Override
    public DrinkAnalysis analyze(String name) {
        if (name == null || name.isBlank()) {
            throw new NutritionExtractionException("drink name is empty");
        }
        Content content = Content.fromParts(
            Part.fromText(EXTRACT_PROMPT),
            Part.fromText("DRINK NAME:\n" + name.strip())
        );
        GenerateContentConfig config = GenerateContentConfig.builder()
            .tools(List.of(extractTool))
            .build();

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(model, content, config);
        } catch (RuntimeException e) {
            log.warn("Drink extraction call failed: {}", e.getMessage());
            throw new NutritionExtractionException("drink extraction failed", e);
        }
        Map<String, Object> args = toolArgs(response, EXTRACT_TOOL);
        if (args == null) {
            throw new NutritionExtractionException("Gemini did not return an extract_drink tool call");
        }
        String resolvedName = str(args.get("name"));
        Macros macros = new Macros(
            null,
            dbl(args.get("proteinGrams")),
            dbl(args.get("carbsGrams")),
            dbl(args.get("fatGrams")),
            dbl(args.get("fiberGrams")),
            dbl(args.get("sugarGrams")));
        return new DrinkAnalysis(
            (resolvedName != null && !resolvedName.isBlank()) ? resolvedName : name.strip(),
            dbl(args.get("abvPercent")),
            dbl(args.get("servingVolumeMl")),
            macros);
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

    private static FunctionDeclaration extractDrinkTool() {
        Schema params = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(orderedMap(
                "name", Schema.builder().type(Type.Known.STRING)
                    .description("Clean, capitalized display name for the drink.").build(),
                "abvPercent", Schema.builder().type(Type.Known.NUMBER)
                    .description("Alcohol by volume of the finished drink, percent.").build(),
                "servingVolumeMl", Schema.builder().type(Type.Known.NUMBER)
                    .description("Typical volume of one serving, in millilitres.").build(),
                "proteinGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "carbsGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fatGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "fiberGrams", Schema.builder().type(Type.Known.NUMBER).build(),
                "sugarGrams", Schema.builder().type(Type.Known.NUMBER)
                    .description("Sugar (g) from mixers for the whole serving; 0 for a neat spirit.").build()
            ))
            .required("name", "abvPercent", "servingVolumeMl")
            .build();
        return FunctionDeclaration.builder()
            .name(EXTRACT_TOOL)
            .description("Return the estimated alcohol facts and mixer macros for the drink.")
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
