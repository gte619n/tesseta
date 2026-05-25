package com.gte619n.healthfitness.integrations.equipment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.equipment.EquipmentParser;
import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses a raw, human-authored gym equipment list into structured
 * {@link ParsedEquipment} records using Gemini. No Google Search grounding —
 * this is a pure structured-extraction step. Fuzzy matching against the
 * existing catalog and persistence happen in higher layers (see Phase 3).
 *
 * @see SpecSchema for the spec schema enum used per-item
 */
@Component
@ConditionalOnProperty(
    name = "app.equipment.parser-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class EquipmentParserService implements EquipmentParser {

    private static final Logger log = LoggerFactory.getLogger(EquipmentParserService.class);

    private static final String SYSTEM_PROMPT = """
        You are a gym equipment classification assistant. Parse the user-provided list
        of gym equipment into a JSON array. Each element represents one piece of equipment.

        Required fields per item:
        - name: Canonical equipment name (e.g., "Treadmill", "Dumbbells", "Leg Press").
          Use the generic name, not the brand-prefixed name.
        - brand: Manufacturer name if mentioned in the input, otherwise null.
        - category: Pick the best fit from this exact list:
            "Free Weights", "Machines - Strength", "Machines - Cardio",
            "Cable Systems", "Benches & Racks", "Bodyweight", "Accessories"

        - subcategory: MUST be one of these canonical values for the chosen category.
          Do not invent new subcategories. If no listed value fits, pick the closest
          match (use "Other" when the category has it):
            "Free Weights":        Barbells, Dumbbells, Kettlebells, Weight Plates, Other
            "Machines - Strength": Chest, Back, Shoulders, Arms, Legs, Core
            "Machines - Cardio":   Treadmill, Elliptical, Stationary Bike, Rowing Machine, Stair Climber, Other
            "Cable Systems":       Single Cable, Dual Cable, Multi-Station
            "Benches & Racks":     Benches, Racks, Stations
            "Bodyweight":          Pull-Up, Dip, Other
            "Accessories":         Supports, Attachments, Mobility
          Examples: a yoga mat -> Accessories / Mobility. A foam roller -> Accessories / Mobility.
          A weight belt -> Accessories / Supports. A leg curl machine -> Machines - Strength / Legs.
          A medicine ball -> Free Weights / Other.
        - specSchema: One of:
            "SELECTORIZED" (pin-select machines),
            "PLATE_LOADED" (barbells, plate-loaded machines, smith machines),
            "BODYWEIGHT" (pull-up bars, dip stations),
            "CABLE" (cable machines, functional trainers),
            "CARDIO" (treadmills, bikes, rowers, ellipticals),
            "WEIGHT_SET" (dumbbell racks, EZ-curl bar sets, kettlebell sets)
        - specs: Object with schema-appropriate fields. For WEIGHT_SET use:
            minWeight (number, lbs), maxWeight (number, lbs), increment (number, lbs),
            and optionally weights (array of numbers) for irregular sets.
          For other schemas, include any specs implied by the input (e.g.,
          CARDIO may include {"hasIncline": true}, SELECTORIZED may include
          {"maxWeight": 200}). Use an empty object {} when no specs are stated.
        - confidence: "CERTAIN", "LIKELY", or "UNCERTAIN". If the input contains
          bracketed markers like [Certain], [Likely], [Unsure], use them. Otherwise
          infer from how specific the input is.
        - rawText: The original input line for this item, verbatim.

        Rules:
        - One JSON object per piece of equipment. If an input line lists multiple
          weight ranges or quantities for the same equipment, output one item.
        - If the input mentions a quantity ("3 treadmills"), still output one item —
          quantity is not stored at this layer.
        - Strip confidence markers like "[Certain]" and trailing periods from name,
          but keep them in rawText.
        - Skip lines that are clearly not equipment (e.g., "Hours: 24/7", "Address: ...").
        - Output ONLY the JSON array. No prose, no markdown fences, no explanation.
        """;

    private final Client client;
    private final String model;
    private final ObjectMapper json;

    public EquipmentParserService(
        @Value("${app.equipment.parser-api-key:}") String apiKey,
        @Value("${app.equipment.parser-model:gemini-3.5-flash}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY is required for equipment parser");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();
    }

    /**
     * Parse a raw equipment list into structured records.
     *
     * @param rawText one item per line, free-form. May include confidence markers.
     * @return parsed records in input order; empty list when input is null/blank.
     * @throws EquipmentParserException if Gemini fails or returns unparseable output.
     */
    @Override
    public List<ParsedEquipment> parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        log.info("Calling Gemini equipment parser: model={}, inputChars={}",
            model, rawText.length());

        Content content = Content.fromParts(
            Part.fromText(SYSTEM_PROMPT),
            Part.fromText("Parse this equipment list:\n\n" + rawText)
        );

        GenerateContentConfig config = GenerateContentConfig.builder().build();

        String text;
        try {
            GenerateContentResponse response =
                client.models.generateContent(model, content, config);
            text = response.text();
        } catch (Exception e) {
            log.error("Gemini equipment parser call failed", e);
            throw new EquipmentParserException(
                "Gemini call failed: " + e.getMessage(), e);
        }

        if (text == null || text.isBlank()) {
            throw new EquipmentParserException(
                "Gemini returned empty response");
        }

        // Clean up response - remove markdown code fences if present
        text = text.trim();
        if (text.startsWith("```json")) {
            text = text.substring(7);
        } else if (text.startsWith("```")) {
            text = text.substring(3);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        text = text.trim();

        if (text.isEmpty()) {
            throw new EquipmentParserException(
                "Gemini returned empty response after fence cleanup");
        }

        if (!text.startsWith("[")) {
            String preview = text.length() > 500 ? text.substring(0, 500) : text;
            throw new EquipmentParserException(
                "Expected JSON array from Gemini but got: " + preview);
        }

        try {
            return json.readValue(text, new TypeReference<List<ParsedEquipment>>() {});
        } catch (Exception e) {
            String preview = text.length() > 500 ? text.substring(0, 500) : text;
            log.error("Failed to parse Gemini equipment JSON. Preview: {}", preview, e);
            throw new EquipmentParserException(
                "Failed to parse Gemini JSON response: " + e.getMessage()
                    + " | preview: " + preview, e);
        }
    }

    public static class EquipmentParserException extends RuntimeException {
        public EquipmentParserException(String message, Throwable cause) {
            super(message, cause);
        }

        public EquipmentParserException(String message) {
            super(message);
        }
    }
}
