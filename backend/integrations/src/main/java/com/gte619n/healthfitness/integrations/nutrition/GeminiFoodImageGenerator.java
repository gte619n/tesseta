package com.gte619n.healthfitness.integrations.nutrition;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageGenerator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Generates plated-food studio images using Gemini's native image generation
 * (model {@code gemini-3.1-flash-image-preview}, the project's image model —
 * root {@code CLAUDE.md}). Mirrors {@code DrugImageGenerator}: the house studio
 * prompt (white marble, soft upper-left light, shallow DoF, 100mm macro,
 * centered negative space, premium editorial, NO text/branding/hands) adapted
 * for plated food, plus the reference-image (multimodal) path.
 *
 * <p>When the food originated from a user meal photo, that photo is passed as a
 * visual reference so the studio shot resembles what was actually eaten (exactly
 * as {@code DrugImageGenerator} uses RxImageAccess reference photos). Otherwise
 * the image is generated from the food name/category alone.
 *
 * <p>Implements the {@code core} {@link FoodImageGenerator} port. Gated by
 * {@code app.nutrition.images.enabled} (default true) so unit-test contexts skip
 * the bean that requires a Gemini API key at construction. Never throws on a
 * generation failure — returns an empty {@link Optional} so the orchestration
 * can mark the food {@code FAILED}.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.images.enabled", havingValue = "true", matchIfMissing = true)
public class GeminiFoodImageGenerator implements FoodImageGenerator {

    private static final Logger log = LoggerFactory.getLogger(GeminiFoodImageGenerator.class);

    /** House studio style applied to every generated plated-food image. */
    private static final String STYLE = """
        PHOTOGRAPHY SPECIFICATIONS:
        - The food beautifully plated on a clean white marble surface
        - Soft diffused natural lighting from the upper left
        - Gentle shadows for depth and dimension
        - Shallow depth of field (f/2.8 aperture equivalent)
        - 100mm macro lens perspective
        - Centered composition with generous negative space
        - Premium editorial food-photography aesthetic
        - High-end style suitable for a health and wellness app

        CRITICAL REQUIREMENTS:
        - Photorealistic rendering of the dish
        - No text, labels, packaging, or branding visible
        - No human hands or body parts
        - No background clutter or props beyond a single clean plate
        - Appetizing, natural portion
        """;

    /** Text-only prompt: generate from the food's name + category. */
    private static final String NAME_PROMPT_TEMPLATE = """
        Generate a professional still-life food photography image.

        SUBJECT: %s

        %s
        """;

    /** Reference-image prompt: the user's real meal photo informs the dish. */
    private static final String REFERENCE_PROMPT_TEMPLATE = """
        The attached image is a real photo of a meal a user logged.

        Generate a NEW professional still-life food photography image of the same
        dish — match the food type, ingredients, color and general plating shown
        in the reference, but re-shoot it cleanly in our consistent studio style.

        SUBJECT FROM REFERENCE: %s

        %s
        - Do NOT copy the reference image — create a new, cleaner studio image of
          the same dish in our style.
        """;

    private final Client client;
    private final String model;

    public GeminiFoodImageGenerator(
        @Value("${app.nutrition.gemini-api-key:}") String apiKey,
        @Value("${app.nutrition.images.model:gemini-3.1-flash-image-preview}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY is required for food image generation");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
    }

    @Override
    public Optional<byte[]> generate(CatalogFood food, byte[] referencePhoto, String referenceMime) {
        if (food == null) {
            return Optional.empty();
        }
        String subject = subjectFor(food);
        if (referencePhoto != null && referencePhoto.length > 0) {
            String prompt = String.format(REFERENCE_PROMPT_TEMPLATE, subject, STYLE);
            String mime = (referenceMime == null || referenceMime.isBlank()) ? "image/jpeg" : referenceMime;
            return executeWithReference(prompt, referencePhoto, mime, food.name());
        }
        String prompt = String.format(NAME_PROMPT_TEMPLATE, subject, STYLE);
        return execute(prompt, food.name());
    }

    /** Human-readable subject: "<name> (<category>)" when a category exists. */
    private static String subjectFor(CatalogFood food) {
        String name = (food.name() == null || food.name().isBlank()) ? "a plated dish" : food.name();
        String category = food.category();
        if (category != null && !category.isBlank()) {
            return name + " (" + category + ")";
        }
        return name;
    }

    private Optional<byte[]> executeWithReference(
        String prompt, byte[] referencePhoto, String mime, String foodName) {
        try {
            Part imagePart = Part.fromBytes(referencePhoto, mime);
            Part textPart = Part.fromText(prompt);
            Content content = Content.fromParts(imagePart, textPart);

            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();

            GenerateContentResponse response = client.models.generateContent(model, content, config);
            return extractImage(response, foodName);
        } catch (Exception e) {
            log.warn("Food image generation with reference failed for {}: {}", foodName, e.getMessage());
            // Fall back to text-only generation.
            return execute(
                prompt.replace("The attached image is a real photo of a meal a user logged.",
                    "Based on the subject description below,"),
                foodName);
        }
    }

    private Optional<byte[]> execute(String prompt, String foodName) {
        try {
            Content content = Content.fromParts(Part.fromText(prompt));
            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();

            GenerateContentResponse response = client.models.generateContent(model, content, config);
            return extractImage(response, foodName);
        } catch (Exception e) {
            log.warn("Food image generation failed for {}: {}", foodName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<byte[]> extractImage(GenerateContentResponse response, String foodName) {
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) {
            log.warn("No candidates in food image response for {}", foodName);
            return Optional.empty();
        }
        Content content = candidates.get(0).content().orElse(null);
        if (content == null) {
            return Optional.empty();
        }
        List<Part> parts = content.parts().orElse(List.of());
        for (Part part : parts) {
            var inlineDataOpt = part.inlineData();
            if (inlineDataOpt.isPresent()) {
                var dataOpt = inlineDataOpt.get().data();
                if (dataOpt.isPresent() && dataOpt.get().length > 0) {
                    return Optional.of(dataOpt.get());
                }
            }
        }
        log.warn("No image data found in food image response for {}", foodName);
        return Optional.empty();
    }
}
