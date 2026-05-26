package com.gte619n.healthfitness.integrations.medication;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Generates medication images using Gemini's native image generation.
 * Uses gemini-3.1-flash-image-preview (configured via app.medications.imagen-model).
 *
 * Image generation is informed by real drug data from:
 * - RxImageAccess (NIH): Real pill photographs used as subject reference
 * - OpenFDA: Dosage form, route, manufacturer
 * - DailyMed SPL: Physical descriptions from drug labels
 *
 * When a real photograph is available from RxImageAccess, it's passed to
 * Gemini as a reference for the subject appearance. The model then generates
 * a new image of a similar pharmaceutical subject using our consistent
 * photography style (marble background, lighting, composition).
 *
 * Uses "still life photography" framing to avoid content filter issues
 * with pharmaceutical imagery.
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugImageGenerator {

    /**
     * Prompt template when we have a reference image from RxImageAccess.
     * The reference image shows the actual pill appearance; we ask Gemini
     * to generate a similar pharmaceutical subject in our consistent style.
     */
    private static final String REFERENCE_IMAGE_PROMPT_TEMPLATE = """
        The attached image is a reference photograph of a real pharmaceutical product.

        Generate a NEW professional still-life product photography image featuring
        a similar pharmaceutical subject — match the pill/medication type, color,
        shape, and general appearance shown in the reference.

        SUBJECT FROM REFERENCE: %s

        ADDITIONAL DATA:
        %s

        PHOTOGRAPHY SPECIFICATIONS (apply these to your generated image):
        - Clean white marble surface as background
        - Soft diffused natural lighting from upper left
        - Gentle shadows for depth and dimension
        - Shallow depth of field (f/2.8 aperture equivalent)
        - 100mm macro lens perspective
        - Centered composition with generous negative space
        - Premium healthcare aesthetic
        - High-end editorial style suitable for a health and wellness app

        CRITICAL REQUIREMENTS:
        - Generate a SINGLE medication item matching the reference subject
        - DO NOT copy the reference image — create a new image in our style
        - No text, labels, or branding visible
        - No human hands or body parts
        - No background clutter or props
        - Photorealistic rendering
        - Match the color and form of the reference pill/medication
        """;

    /**
     * Enhanced prompt template with data source context (no reference image).
     */
    private static final String ENHANCED_PROMPT_TEMPLATE = """
        Generate a professional still-life product photography image.

        SUBJECT: %s

        DATA SOURCES INFORMING THIS REQUEST:
        %s

        PHOTOGRAPHY SPECIFICATIONS:
        - Clean white marble surface as background
        - Soft diffused natural lighting from upper left
        - Gentle shadows for depth and dimension
        - Shallow depth of field (f/2.8 aperture equivalent)
        - 100mm macro lens perspective
        - Centered composition with generous negative space
        - Premium healthcare aesthetic
        - High-end editorial style suitable for a health and wellness app

        CRITICAL REQUIREMENTS:
        - Single medication item only
        - No text, labels, or branding visible
        - No human hands or body parts
        - No background clutter or props
        - Photorealistic rendering
        - Accurate color representation as specified
        """;

    /**
     * Fallback prompt for when no visual data is available.
     */
    private static final String FALLBACK_PROMPT_TEMPLATE = """
        Generate a professional still-life product photography image of a single %s,
        centered on a clean white marble surface. Soft diffused natural lighting from
        upper left, creating gentle shadows. Shallow depth of field with f/2.8 aperture.
        Shot with a 100mm macro lens. Clean, minimal composition with negative space.
        Premium healthcare aesthetic. No text, no labels, no branding. High-end editorial
        style suitable for a health and wellness publication.
        """;

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    private final Client client;
    private final String model;
    private final HttpClient httpClient;

    public DrugImageGenerator(
        @Value("${app.medications.gemini-api-key:}") String apiKey,
        @Value("${app.medications.imagen-model}") String model
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY is required for image generation");
        }
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(FETCH_TIMEOUT)
            .build();
    }

    /**
     * Generate a medication image using visual data from NIH/FDA sources.
     * If a real image is available from RxImageAccess, it's used as a subject reference.
     *
     * @param visualInfo Visual characteristics from RxImageAccess/OpenFDA/DailyMed
     * @return The generated image as PNG bytes, or empty if generation failed
     */
    public Optional<byte[]> generate(DrugVisualLookupService.DrugVisualInfo visualInfo) {
        String subject = visualInfo.toPromptDescription();
        String dataSources = buildDataSourcesDescription(visualInfo);

        // If we have a real image URL, try to use it as a reference
        if (visualInfo.realImageUrl() != null) {
            Optional<byte[]> referenceImage = fetchReferenceImage(visualInfo.realImageUrl());
            if (referenceImage.isPresent()) {
                System.out.println("Using RxImageAccess reference image for " + visualInfo.drugName());
                String prompt = String.format(REFERENCE_IMAGE_PROMPT_TEMPLATE, subject, dataSources);
                return executeGenerationWithReference(prompt, referenceImage.get(), visualInfo.drugName());
            }
        }

        // No reference image available, use text-only prompt
        String prompt = String.format(ENHANCED_PROMPT_TEMPLATE, subject, dataSources);
        return executeGeneration(prompt, visualInfo.drugName());
    }

    /**
     * Generate a medication image using basic form information.
     * Fallback for when no visual data is available.
     *
     * @param drugName The name of the drug (for logging)
     * @param form The physical form (INJECTABLE_VIAL, TABLET, etc.)
     * @return The generated image as PNG bytes, or empty if generation failed
     */
    public Optional<byte[]> generate(String drugName, String form) {
        String subject = getGenericSubject(form, drugName);
        String prompt = String.format(FALLBACK_PROMPT_TEMPLATE, subject);

        return executeGeneration(prompt, drugName);
    }

    /**
     * Build the default prompt that would be used for this drug. Used by the
     * admin UI to prefill an editable prompt before regeneration. Mirrors
     * the fallback (text-only) path; the visual-data path requires a live
     * lookup which would happen at generation time anyway.
     */
    public String defaultPromptFor(com.gte619n.healthfitness.core.medication.Drug drug) {
        String subject = getGenericSubject(drug.form().name(), drug.name());
        return String.format(FALLBACK_PROMPT_TEMPLATE, subject);
    }

    /**
     * Run image generation using an explicit prompt — used by admin regen
     * after an editable-prompt review. Returns image bytes (PNG) or empty.
     */
    public Optional<byte[]> generateWithPrompt(String prompt, String drugName) {
        return executeGeneration(prompt, drugName);
    }

    /**
     * Fetch a reference image from RxImageAccess URL.
     */
    private Optional<byte[]> fetchReferenceImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200 && response.body().length > 0) {
                return Optional.of(response.body());
            }

            System.err.println("Failed to fetch reference image: HTTP " + response.statusCode());
            return Optional.empty();

        } catch (Exception e) {
            System.err.println("Failed to fetch reference image: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Build a description of what data sources informed this image request.
     */
    private String buildDataSourcesDescription(DrugVisualLookupService.DrugVisualInfo info) {
        StringBuilder sb = new StringBuilder();

        if (info.color() != null || info.shape() != null) {
            sb.append("- Physical characteristics: ");
            if (info.color() != null) sb.append("color: ").append(info.color());
            if (info.shape() != null) {
                if (info.color() != null) sb.append(", ");
                sb.append("shape: ").append(info.shape());
            }
            if (info.imprint() != null) sb.append(", imprint: '").append(info.imprint()).append("'");
            if (info.size() != null) sb.append(", size: ").append(info.size()).append("mm");
            sb.append("\n");
        }

        if (info.dosageForm() != null || info.route() != null) {
            sb.append("- Form: ");
            if (info.dosageForm() != null) sb.append(info.dosageForm());
            if (info.route() != null) {
                if (info.dosageForm() != null) sb.append(", ");
                sb.append("route: ").append(info.route());
            }
            sb.append("\n");
        }

        if (info.brandName() != null) {
            sb.append("- Brand: ").append(info.brandName());
            if (info.manufacturer() != null) sb.append(" (").append(info.manufacturer()).append(")");
            sb.append("\n");
        }

        if (info.physicalDescription() != null) {
            sb.append("- Description: ").append(info.physicalDescription()).append("\n");
        }

        if (sb.isEmpty()) {
            sb.append("- No additional data available\n");
        }

        return sb.toString();
    }

    /**
     * Generic subject descriptions when no visual data is available.
     */
    private String getGenericSubject(String form, String drugName) {
        // Check for known pen injector drugs
        if (isLikelyPenInjector(drugName)) {
            return "modern prefilled injection pen with dose dial and protective cap, sleek medical device design";
        }

        return switch (form) {
            case "INJECTABLE_VIAL" -> "clear glass medication vial with rubber stopper, containing clear liquid";
            case "TABLET" -> "small round pharmaceutical tablet";
            case "CAPSULE" -> "two-tone gelatin capsule (half white, half colored)";
            case "SOFTGEL" -> "amber-colored oval softgel supplement";
            case "CREAM" -> "white pharmaceutical cream in a small elegant jar";
            case "PATCH" -> "beige transdermal patch";
            case "LIQUID" -> "amber glass dropper bottle with clear liquid";
            case "POWDER" -> "white powder supplement in a small glass dish";
            default -> "pharmaceutical product";
        };
    }

    /**
     * Check if drug is likely a pen injector based on name.
     */
    private boolean isLikelyPenInjector(String drugName) {
        if (drugName == null) return false;
        String lower = drugName.toLowerCase();

        return lower.contains("mounjaro") ||
               lower.contains("ozempic") ||
               lower.contains("wegovy") ||
               lower.contains("zepbound") ||
               lower.contains("trulicity") ||
               lower.contains("victoza") ||
               lower.contains("saxenda") ||
               lower.contains("tirzepatide") ||
               lower.contains("semaglutide") ||
               lower.contains("liraglutide") ||
               lower.contains("dulaglutide") ||
               lower.contains("insulin") ||
               lower.contains("lantus") ||
               lower.contains("humalog") ||
               lower.contains("novolog");
    }

    /**
     * Execute image generation with a reference image (multimodal input).
     */
    private Optional<byte[]> executeGenerationWithReference(String prompt, byte[] referenceImage, String drugName) {
        try {
            // Build multimodal content: reference image + text prompt
            // Part.fromBytes takes byte[] and mimeType string
            Part imagePart = Part.fromBytes(referenceImage, "image/jpeg");
            Part textPart = Part.fromText(prompt);

            // Content.fromParts takes varargs
            Content content = Content.fromParts(imagePart, textPart);

            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();

            GenerateContentResponse response = client.models.generateContent(model, content, config);

            return extractImageFromResponse(response, drugName);

        } catch (Exception e) {
            System.err.println("Image generation with reference failed for " + drugName + ": " + e.getMessage());
            // Fall back to text-only generation
            return executeGeneration(prompt.replace("The attached image is a reference", "Based on the subject description"), drugName);
        }
    }

    /**
     * Execute text-only image generation request to Gemini.
     */
    private Optional<byte[]> executeGeneration(String prompt, String drugName) {
        try {
            Content content = Content.fromParts(Part.fromText(prompt));

            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();

            GenerateContentResponse response = client.models.generateContent(model, content, config);

            return extractImageFromResponse(response, drugName);

        } catch (Exception e) {
            System.err.println("Image generation failed for " + drugName + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract image bytes from Gemini response.
     */
    private Optional<byte[]> extractImageFromResponse(GenerateContentResponse response, String drugName) {
        List<com.google.genai.types.Candidate> candidates =
            response.candidates().orElse(List.of());

        if (candidates.isEmpty()) {
            System.err.println("No candidates in image generation response for " + drugName);
            return Optional.empty();
        }

        com.google.genai.types.Candidate candidate = candidates.get(0);
        com.google.genai.types.Content responseContent = candidate.content().orElse(null);

        if (responseContent == null) {
            System.err.println("No content in candidate for " + drugName);
            return Optional.empty();
        }

        List<com.google.genai.types.Part> parts =
            responseContent.parts().orElse(List.of());

        if (parts.isEmpty()) {
            System.err.println("No parts in content for " + drugName);
            return Optional.empty();
        }

        // Look for inline data (image) in parts
        for (com.google.genai.types.Part part : parts) {
            var inlineDataOpt = part.inlineData();
            if (inlineDataOpt.isPresent()) {
                var inlineData = inlineDataOpt.get();
                var dataOpt = inlineData.data();
                if (dataOpt.isPresent()) {
                    byte[] imageBytes = dataOpt.get();
                    if (imageBytes.length > 0) {
                        return Optional.of(imageBytes);
                    }
                }
            }
        }

        System.err.println("No image data found in response for " + drugName);
        return Optional.empty();
    }

    /**
     * Get a fallback image URL based on drug form.
     * These are pre-generated static images stored in GCS.
     *
     * @param form The physical form
     * @return URL to the fallback image
     */
    public static String getFallbackUrl(String form, String bucket) {
        String filename = switch (form) {
            case "INJECTABLE_VIAL" -> "fallback-vial.png";
            case "TABLET" -> "fallback-tablet.png";
            case "CAPSULE" -> "fallback-capsule.png";
            case "SOFTGEL" -> "fallback-softgel.png";
            case "CREAM" -> "fallback-cream.png";
            case "PATCH" -> "fallback-patch.png";
            case "LIQUID" -> "fallback-liquid.png";
            case "POWDER" -> "fallback-powder.png";
            default -> "fallback-generic.png";
        };
        return "https://storage.googleapis.com/" + bucket + "/fallbacks/" + filename;
    }
}
