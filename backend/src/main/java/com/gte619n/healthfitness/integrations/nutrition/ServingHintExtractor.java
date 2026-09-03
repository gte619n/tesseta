package com.gte619n.healthfitness.integrations.nutrition;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.nutrition.ServingHintAnalyzer;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gemini-backed {@link ServingHintAnalyzer}: turns a logged portion into a short,
 * everyday-language "typical serving" explanation the user can picture (cups,
 * pieces, tablespoons, a deck of cards…). A plain text completion on the SHARED
 * flash model ({@code app.nutrition.gemini-model} / {@code GEMINI_MODEL}), sibling
 * to {@link MealDescriptionExtractor}; gated by {@code app.nutrition.capture.enabled}
 * so test contexts skip the live bean.
 *
 * <p>Never throws: a failed or empty completion yields {@link Optional#empty()},
 * so the edit sheet simply shows no hint rather than an error.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class ServingHintExtractor implements ServingHintAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(ServingHintExtractor.class);

    private static final String PROMPT = """
        You help a nutrition-tracking user picture how much food a logged portion
        actually is. Given a food (or a prepared meal and its components) and the
        logged weight in grams, write ONE short, concrete sentence describing that
        portion in everyday household terms — cups, tablespoons, pieces, slices, a
        common object for size — so the amount is easy to visualise.

        Rules:
        - One sentence, under ~18 words. No preamble, no macros, no calories.
        - Lead with the household measure, then the grams in parentheses, e.g.
          "About ¾ cup of blueberries (110 g)." or "Roughly 2 slices (60 g)."
        - For a prepared meal, describe the overall plate ("about one dinner-plate
          serving"), not every ingredient.
        - If the weight is unknown, describe a single typical serving instead.
        - Plain text only. Do not use markdown.
        """;

    private final Client client;
    private final String model;

    public ServingHintExtractor(
        Client client,
        @Value("${app.nutrition.gemini-model:${GEMINI_MODEL:gemini-3.5-flash}}") String model
    ) {
        this.client = client;
        this.model = model;
    }

    @Override
    public Optional<String> describeServing(
        String name, Double grams, String servingLabel, List<String> components) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        StringBuilder facts = new StringBuilder();
        facts.append("FOOD: ").append(name.strip());
        if (grams != null && grams > 0) {
            facts.append("\nLOGGED WEIGHT: ").append(Math.round(grams)).append(" g");
        }
        if (servingLabel != null && !servingLabel.isBlank()) {
            facts.append("\nSERVING LABEL: ").append(servingLabel.strip());
        }
        if (components != null && !components.isEmpty()) {
            facts.append("\nCOMPONENTS: ").append(String.join(", ", components));
        }
        Content content = Content.fromParts(Part.fromText(PROMPT), Part.fromText(facts.toString()));
        try {
            GenerateContentResponse response =
                client.models.generateContent(model, content, GenerateContentConfig.builder().build());
            String text = response.text();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(text.strip());
        } catch (RuntimeException e) {
            log.warn("Serving-hint generation failed for {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }
}
