package com.gte619n.healthfitness.integrations.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

// Generates AI images for equipment using Gemini 3.1 Flash (Nano quality).
//
// The service builds a photography prompt from the equipment metadata
// following the style guide in docs/photography-prompts.md, calls the
// Gemini 3.1 Flash image generation API with nano quality tier for fast,
// cost-effective generation, uploads the result to GCS, and returns the
// public URL.
//
// Model: gemini-3.1-flash-image-preview
// Quality: nano (fastest, most cost-effective)
//
// Image generation is async; the caller receives a CompletableFuture that
// resolves to the GCS URL or null if generation fails.
@Service
public class EquipmentImageService {

    private static final Logger log = LoggerFactory.getLogger(EquipmentImageService.class);

    private final EquipmentImageStorage storage;
    // TODO: Inject Gemini 3.1 Flash client (gemini-3.1-flash-image-preview)
    // with nano quality tier for cost-effective image generation

    public EquipmentImageService(EquipmentImageStorage storage) {
        this.storage = storage;
    }

    /**
     * Generate an AI image for the equipment and upload to GCS.
     * Returns a CompletableFuture that resolves to the public GCS URL,
     * or null if generation fails.
     */
    public CompletableFuture<String> generateImage(Equipment equipment) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = buildPrompt(equipment);
                String negativePrompt = getNegativePrompt();

                log.info("Generating image for equipment {} with prompt: {}",
                    equipment.equipmentId(), prompt);

                // TODO: Call Gemini 3.1 Flash API (nano quality) when SDK is integrated
                // Model: gemini-3.1-flash-image-preview
                // Quality: nano
                // For now, this is stubbed to return null
                // byte[] imageBytes = callGeminiFlashAPI(prompt, negativePrompt, "nano");
                // if (imageBytes != null) {
                //     return storage.upload(equipment.equipmentId(), imageBytes);
                // }

                log.warn("Image generation not yet implemented, returning null");
                return null;

            } catch (Exception e) {
                log.error("Failed to generate image for equipment {}: {}",
                    equipment.equipmentId(), e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Build the complete photography prompt from equipment metadata.
     * Follows the style guide in docs/photography-prompts.md.
     */
    public String buildPrompt(Equipment equipment) {
        String sharedTreatment = """
            Warm neutral seamless background, oatmeal color, hex F0EBE0 or a hair
            lighter. Soft diffuse daylight from a single direction, large soft
            source, gentle realistic shadows, no hard flash, no studio specular
            hotspots. Muted natural color, slightly desaturated. Matte finish, no
            glossy advertising sheen. Subject centered with generous negative
            space. Photographic realism, full-frame camera look, 50mm to 85mm
            equivalent lens, moderate depth of field. No text, no graphics, no
            logos, no props beyond what is specified. Quiet, editorial,
            instrument-like mood — a precision-tool catalog, not a supplement ad.
            """;

        String framingClause = """
            A single piece of equipment, isolated, centered, no gym environment
            around it, no people. A soft realistic contact shadow grounds the
            object so it does not float.
            """;

        String materialsClause = """
            Realistic materials with honest light wear, not showroom-pristine —
            brushed steel, knurled iron, matte rubber, worn leather, warm wood
            where present.
            """;

        String equipmentDescription = buildEquipmentDescription(equipment);

        return String.format("""
            %s
            %s
            %s
            Three-quarter angle.
            The object is: %s.
            """, sharedTreatment, framingClause, materialsClause, equipmentDescription);
    }

    /**
     * Build a natural language description of the equipment from its metadata.
     * Converts structured data (name, category, specs) into a descriptive phrase
     * suitable for image generation.
     */
    private String buildEquipmentDescription(Equipment equipment) {
        StringBuilder desc = new StringBuilder();
        desc.append(equipment.name());

        // Add spec-specific details based on schema
        if (equipment.specs() != null && equipment.specSchema() != null) {
            switch (equipment.specSchema()) {
                case PLATE_LOADED -> {
                    Object barWeight = equipment.specs().get("barWeight");
                    if (barWeight != null) {
                        desc.append(", with ").append(barWeight).append("lb bar");
                    }
                }
                case SELECTORIZED -> {
                    Object maxWeight = equipment.specs().get("maxWeight");
                    if (maxWeight != null) {
                        desc.append(", weight stack up to ").append(maxWeight).append("lbs");
                    }
                }
                case CABLE -> {
                    Object stacks = equipment.specs().get("weightStacks");
                    if (stacks != null) {
                        desc.append(", with ").append(stacks).append(" weight stack");
                        if (!"1".equals(stacks.toString())) {
                            desc.append("s");
                        }
                    }
                }
                case CARDIO -> {
                    Object type = equipment.specs().get("type");
                    if (type != null) {
                        desc.append(", ").append(type).append(" type");
                    }
                }
                case BODYWEIGHT -> {
                    Object adjustable = equipment.specs().get("adjustable");
                    if (Boolean.TRUE.equals(adjustable)) {
                        desc.append(", height-adjustable");
                    }
                }
            }
        }

        // Add material hints based on category
        String materialHint = switch (equipment.category()) {
            case "Free Weights" -> ", iron and steel construction";
            case "Machines - Strength", "Machines - Cardio" ->
                ", steel frame with padded upholstery";
            case "Cable Systems" -> ", steel frame with cable pulleys";
            case "Benches & Racks" -> ", steel frame with padded surface";
            case "Bodyweight" -> ", steel construction";
            case "Accessories" -> ", professional gym equipment";
            default -> "";
        };
        desc.append(materialHint);

        return desc.toString();
    }

    /**
     * Get the negative prompt to exclude unwanted elements from generated images.
     */
    private String getNegativePrompt() {
        return """
            text, watermark, logo, label text, brand name, neon colors, vivid
            saturation, hard flash, studio glamour, lens flare, busy background,
            gym environment clutter, multiple competing objects, cartoon,
            illustration, 3d render, plastic look
            """;
    }

    /**
     * Upload a generated image to GCS and return the public URL.
     * This is a helper method for when the Imagen API is implemented.
     */
    @SuppressWarnings("unused")
    private String uploadImage(byte[] imageData, String equipmentId) {
        return storage.upload(equipmentId, imageData);
    }
}
