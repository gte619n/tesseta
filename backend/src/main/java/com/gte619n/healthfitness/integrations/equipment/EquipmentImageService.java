package com.gte619n.healthfitness.integrations.equipment;

import com.google.genai.Client;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentImageGenerator;
import com.gte619n.healthfitness.core.equipment.EquipmentImageUploader;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.ImageStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Generates AI images for equipment using Gemini 3.1 Flash image generation.
//
// The service builds a photography prompt from the equipment metadata
// following the style guide in docs/photography-prompts.md, calls the
// Gemini image-generation API, uploads the resulting bytes to GCS, and
// updates the Equipment's imageUrl + imageStatus via the repository.
//
// Model: gemini-3.1-flash-image-preview (configured via app.equipment.gemini-model)
//
// Async fire-and-forget: generateImageAsync returns immediately; the work
// happens on a background thread via CompletableFuture.runAsync.
@Component
public class EquipmentImageService implements EquipmentImageGenerator, EquipmentImageUploader {

    private static final Logger log = LoggerFactory.getLogger(EquipmentImageService.class);

    private final EquipmentImageStorage storage;
    private final EquipmentRepository equipmentRepository;
    private final Client client;
    private final String model;

    public EquipmentImageService(
        EquipmentImageStorage storage,
        EquipmentRepository equipmentRepository,
        @Value("${app.equipment.gemini-api-key:${GEMINI_API_KEY:}}") String apiKey,
        @Value("${app.equipment.gemini-model:gemini-3.1-flash-image-preview}") String model
    ) {
        this.storage = storage;
        this.equipmentRepository = equipmentRepository;
        this.model = model;
        if (apiKey == null || apiKey.isBlank()) {
            // Allow startup without a key so non-image-gen flows still work;
            // an actual generation attempt will fail loudly and mark the
            // equipment FAILED. This matches how other Gemini-backed beans
            // behave when the key is absent in local dev.
            log.warn("GEMINI_API_KEY not set — equipment image generation will fail until configured");
            this.client = null;
        } else {
            this.client = Client.builder().apiKey(apiKey).build();
        }
    }

    /**
     * Kick off async image generation. Returns immediately with a future
     * that completes (always normally — exceptions are swallowed and the
     * equipment is marked FAILED) when the underlying job finishes. On
     * completion, the equipment's imageUrl + imageStatus are updated in
     * the repository.
     */
    @Override
    public CompletableFuture<Void> generateImageAsync(Equipment equipment) {
        return generateImageAsync(equipment, null);
    }

    @Override
    public String defaultPrompt(Equipment equipment) {
        return buildPrompt(equipment);
    }

    /**
     * Store an admin-supplied image for the equipment. Synchronous: the
     * bytes are already in hand, so we upload, append the new url to the
     * candidate gallery, mark the record GENERATED with the new active
     * imageUrl, and return the updated equipment. Prior images are kept as
     * candidates. Mirrors the success path of {@link #generateImageAsync}
     * sans Gemini.
     */
    @Override
    public Equipment uploadImage(String equipmentId, byte[] bytes, String contentType) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        String url = storage.upload(equipmentId, bytes, contentType);
        // Keep prior images as candidates — the new url is appended and
        // becomes the active one; nothing is deleted from storage here.
        persistImageResult(equipment, url, ImageStatus.GENERATED);
        log.info("Uploaded admin image for equipment {}: {}", equipmentId, url);

        return equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));
    }

    @Override
    public CompletableFuture<Void> generateImageAsync(Equipment equipment, String promptOverride) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (client == null) {
                    log.warn("Skipping image generation for {} — no API key configured",
                        equipment.equipmentId());
                    persistImageResult(equipment, null, ImageStatus.FAILED);
                    return;
                }

                String prompt = (promptOverride != null && !promptOverride.isBlank())
                    ? promptOverride
                    : buildPrompt(equipment);
                log.info("Generating image for equipment {} ({}): model={}{}",
                    equipment.equipmentId(), equipment.name(), model,
                    promptOverride != null ? " (prompt overridden)" : "");

                byte[] bytes = callGemini(prompt, equipment.equipmentId());

                if (bytes != null && bytes.length > 0) {
                    String url = storage.upload(equipment.equipmentId(), bytes);
                    // Keep prior images as candidates — the new url is
                    // appended and becomes the active one; nothing is
                    // deleted from storage here.
                    persistImageResult(equipment, url, ImageStatus.GENERATED);
                    log.info("Generated image for equipment {}: {}",
                        equipment.equipmentId(), url);
                } else {
                    persistImageResult(equipment, null, ImageStatus.FAILED);
                    log.warn("Image generation returned empty bytes for equipment {}",
                        equipment.equipmentId());
                }
            } catch (Exception e) {
                log.error("Failed to generate image for equipment {}: {}",
                    equipment.equipmentId(), e.getMessage(), e);
                try {
                    persistImageResult(equipment, null, ImageStatus.FAILED);
                } catch (Exception ignore) {
                    // Don't let a follow-up persist failure mask the original error.
                }
            }
        });
    }

    /**
     * Re-read the equipment fresh from the repository before writing — the
     * Equipment record we were handed may be stale (e.g. ownerId cleared
     * by an approve call between submit and image completion).
     *
     * <p>When {@code imageUrl} is non-null it is APPENDED to the candidate
     * gallery (de-duplicated) and becomes the active image; prior images are
     * kept as candidates. A null url (e.g. a FAILED result) leaves the
     * existing candidates untouched.
     */
    private void persistImageResult(Equipment original, String imageUrl, ImageStatus status) {
        Equipment current = equipmentRepository.findById(original.equipmentId())
            .orElse(original);

        List<String> candidates = imageUrl == null
            ? current.imageCandidates()
            : appended(current.imageCandidates(), imageUrl);

        Equipment updated = new Equipment(
            current.equipmentId(),
            current.name(),
            current.category(),
            current.subcategory(),
            current.specSchema(),
            current.specs(),
            imageUrl,
            candidates,
            status,
            current.ownerId(),
            current.status(),
            current.contributorId(),
            current.exerciseCount(),
            current.createdAt(),
            Instant.now(),
            current.aliasOfEquipmentId()
        );
        equipmentRepository.save(updated);
    }

    /**
     * Return {@code existing} with {@code url} appended, de-duplicated and
     * preserving insertion order. Tolerates a null existing list.
     */
    private List<String> appended(List<String> existing, String url) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(
            existing == null ? List.of() : existing);
        merged.add(url);
        return new ArrayList<>(merged);
    }

    @Override
    public Equipment deleteImage(String equipmentId, String imageUrl) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        List<String> candidates = equipment.imageCandidates();
        if (candidates == null || !candidates.contains(imageUrl)) {
            throw new IllegalArgumentException("Image is not a candidate for this equipment");
        }

        List<String> remaining = new ArrayList<>(candidates);
        remaining.remove(imageUrl);

        // Best-effort GCS cleanup — never let a storage hiccup block the
        // metadata update.
        try {
            storage.deleteByUrl(imageUrl);
        } catch (Exception cleanupErr) {
            log.warn("Failed to delete equipment image blob for {}: {}",
                equipmentId, cleanupErr.getMessage());
        }

        // If we just removed the active image, fall back to the first
        // remaining candidate (or null when the gallery is now empty).
        String activeUrl = imageUrl.equals(equipment.imageUrl())
            ? (remaining.isEmpty() ? null : remaining.get(0))
            : equipment.imageUrl();

        Equipment updated = new Equipment(
            equipment.equipmentId(),
            equipment.name(),
            equipment.category(),
            equipment.subcategory(),
            equipment.specSchema(),
            equipment.specs(),
            activeUrl,
            remaining,
            equipment.imageStatus(),
            equipment.ownerId(),
            equipment.status(),
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            Instant.now(),
            equipment.aliasOfEquipmentId()
        );
        equipmentRepository.save(updated);
        return updated;
    }

    /**
     * Call Gemini image generation and extract the first image bytes from
     * the response. Mirrors {@code DrugImageGenerator.executeGeneration}.
     */
    byte[] callGemini(String prompt, String equipmentId) {
        try {
            Content content = Content.fromParts(Part.fromText(prompt));

            GenerateContentConfig config = GenerateContentConfig.builder()
                .responseModalities(List.of("IMAGE", "TEXT"))
                .build();

            GenerateContentResponse response = client.models.generateContent(model, content, config);

            return extractImageBytes(response, equipmentId);
        } catch (Exception e) {
            log.error("Gemini image generation call failed for equipment {}: {}",
                equipmentId, e.getMessage(), e);
            return null;
        }
    }

    private byte[] extractImageBytes(GenerateContentResponse response, String equipmentId) {
        List<Candidate> candidates = response.candidates().orElse(List.of());
        if (candidates.isEmpty()) {
            log.warn("No candidates in image generation response for equipment {}", equipmentId);
            return null;
        }

        Content responseContent = candidates.get(0).content().orElse(null);
        if (responseContent == null) {
            log.warn("No content in candidate for equipment {}", equipmentId);
            return null;
        }

        List<Part> parts = responseContent.parts().orElse(List.of());
        for (Part part : parts) {
            var inlineDataOpt = part.inlineData();
            if (inlineDataOpt.isPresent()) {
                var dataOpt = inlineDataOpt.get().data();
                if (dataOpt.isPresent() && dataOpt.get().length > 0) {
                    return dataOpt.get();
                }
            }
        }
        log.warn("No image data found in Gemini response for equipment {}", equipmentId);
        return null;
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
            Override the shared treatment for this category: render as professional
            product photography of real, modern commercial gym equipment — the kind
            of catalog and website imagery used by premium brands like Rogue Fitness,
            Eleiko, Matrix Fitness, Life Fitness, Cybex, Hammer Strength, Precor, and
            Technogym. Photorealistic, DSLR-grade sharpness, accurate true-to-life
            colors (not muted or desaturated), natural specular highlights on steel
            and chrome, realistic surface reflections on plastic and upholstery,
            glossy where the real material is glossy. Pristine showroom condition,
            brand-new and unused — factory-fresh finishes, perfectly clean, no
            scratches, scuffs, rust, patina, or signs of use. Industrial design
            language of high-end commercial fitness equipment. Materials present
            where appropriate: polished or brushed steel, knurled iron, anodized
            aluminum, high-density rubber grips and flooring, fresh leather or vinyl
            upholstery with crisp stitching, ABS plastic shrouds, color-coded
            weight plates.
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
                case WEIGHT_SET -> {
                    Object minWeight = equipment.specs().get("minWeight");
                    Object maxWeight = equipment.specs().get("maxWeight");
                    Object increment = equipment.specs().get("increment");
                    Object weights = equipment.specs().get("weights");
                    if (minWeight instanceof Number min && maxWeight instanceof Number max) {
                        desc.append(", weight set ranging from ")
                            .append(min.intValue()).append("lb to ")
                            .append(max.intValue()).append("lb");
                        if (increment instanceof Number inc) {
                            desc.append(" in ").append(inc.intValue()).append("lb increments");
                        }
                    } else if (weights instanceof java.util.List<?> list && !list.isEmpty()) {
                        desc.append(", weight set including ").append(list.size()).append(" pieces");
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
}
