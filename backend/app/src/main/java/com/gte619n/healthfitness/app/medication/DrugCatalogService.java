package com.gte619n.healthfitness.app.medication;

import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugCategory;
import com.gte619n.healthfitness.core.medication.DrugForm;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.integrations.medication.DrugImageGenerator;
import com.gte619n.healthfitness.integrations.medication.DrugImageStorage;
import com.gte619n.healthfitness.integrations.medication.DrugLookupService;
import com.gte619n.healthfitness.integrations.medication.DrugLookupService.DrugLookupResult;
import com.gte619n.healthfitness.integrations.medication.DrugVisualLookupService;
import com.gte619n.healthfitness.integrations.medication.DrugVisualLookupService.DrugVisualInfo;
import com.gte619n.healthfitness.integrations.medication.RxNormLookupService;
import com.gte619n.healthfitness.integrations.medication.RxNormLookupService.RxNormDrugResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates drug catalog operations including:
 * - RxNorm-first drug lookup with AI fallback
 * - Visual data enrichment from RxImageAccess, OpenFDA, and DailyMed
 * - AI image generation informed by actual drug characteristics
 * - Catalog deduplication
 */
@Service
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugCatalogService {

    private final DrugRepository drugs;
    private final MedicationRepository medications;
    private final RxNormLookupService rxNormService;
    private final DrugLookupService aiLookupService;
    private final DrugVisualLookupService visualLookupService;
    private final DrugImageGenerator imageGenerator;
    private final DrugImageStorage imageStorage;
    private final String bucket;

    public DrugCatalogService(
        DrugRepository drugs,
        MedicationRepository medications,
        RxNormLookupService rxNormService,
        DrugLookupService aiLookupService,
        DrugVisualLookupService visualLookupService,
        DrugImageGenerator imageGenerator,
        DrugImageStorage imageStorage,
        @Value("${app.medications.bucket}") String bucket
    ) {
        this.drugs = drugs;
        this.medications = medications;
        this.rxNormService = rxNormService;
        this.aiLookupService = aiLookupService;
        this.visualLookupService = visualLookupService;
        this.imageGenerator = imageGenerator;
        this.imageStorage = imageStorage;
        this.bucket = bucket;
    }

    /**
     * Search the existing drug catalog by name.
     */
    public List<Drug> search(String query) {
        if (query == null || query.isBlank()) {
            return drugs.findAll();
        }
        return drugs.search(query);
    }

    /**
     * Look up a drug using RxNorm (NIH/NLM) first, with AI fallback.
     * If the drug exists in the catalog, returns the existing entry.
     * If not, creates a new entry with RxNorm or AI-generated metadata.
     *
     * @param query User's search query
     * @return The drug (existing or newly created), or empty if not found
     */
    public Optional<Drug> lookupOrCreate(String query) {
        // First, check if we have an exact or close match in the catalog
        List<Drug> existing = drugs.search(query);
        if (!existing.isEmpty()) {
            // Return the best match
            return Optional.of(existing.get(0));
        }

        // Try RxNorm first (free, fast, no API key needed)
        Optional<RxNormDrugResult> rxResult = rxNormService.lookup(query);
        if (rxResult.isPresent()) {
            RxNormDrugResult rx = rxResult.get();

            // Check again by canonical name (in case user searched by alias)
            existing = drugs.search(rx.name());
            if (!existing.isEmpty()) {
                return Optional.of(existing.get(0));
            }

            // RxNorm doesn't provide suggestedMarkers or description
            // Try to enrich with AI if needed
            List<String> suggestedMarkers = rx.suggestedMarkers();
            String description = rx.description();

            if ((suggestedMarkers == null || suggestedMarkers.isEmpty()) || description == null) {
                try {
                    DrugLookupResult aiResult = aiLookupService.lookup(query);
                    if (aiResult != null) {
                        if (suggestedMarkers == null || suggestedMarkers.isEmpty()) {
                            suggestedMarkers = aiResult.suggestedMarkers();
                        }
                        if (description == null) {
                            description = aiResult.description();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("AI enrichment failed (non-fatal): " + e.getMessage());
                }
            }

            return Optional.of(createDrugFromRxNorm(rx, suggestedMarkers, description));
        }

        // RxNorm failed - fall back to AI lookup (Gemini with Google Search)
        try {
            DrugLookupResult result = aiLookupService.lookup(query);
            if (result == null) {
                return Optional.empty();
            }

            // Check again by canonical name (in case user searched by alias)
            existing = drugs.search(result.name());
            if (!existing.isEmpty()) {
                return Optional.of(existing.get(0));
            }

            return Optional.of(createDrugFromAiResult(result));
        } catch (Exception e) {
            System.err.println("AI lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Look up a drug with progress callbacks for SSE streaming.
     * Same as lookupOrCreate but emits progress events.
     *
     * @param query User's search query
     * @param progress Callback for progress updates (phase, message)
     * @return The drug (existing or newly created), or empty if not found
     */
    public Optional<Drug> lookupOrCreateWithCallback(String query, BiConsumer<String, String> progress) {
        // First, check if we have an exact or close match in the catalog
        List<Drug> existing = drugs.search(query);
        if (!existing.isEmpty()) {
            progress.accept("found", "Found in catalog");
            return Optional.of(existing.get(0));
        }

        progress.accept("searching_rxnorm", "Searching RxNorm database...");

        // Try RxNorm first (free, fast, no API key needed)
        Optional<RxNormDrugResult> rxResult = rxNormService.lookup(query);
        if (rxResult.isPresent()) {
            RxNormDrugResult rx = rxResult.get();
            progress.accept("found", "Found: " + rx.name());

            // Check again by canonical name (in case user searched by alias)
            existing = drugs.search(rx.name());
            if (!existing.isEmpty()) {
                return Optional.of(existing.get(0));
            }

            // RxNorm doesn't provide suggestedMarkers or description
            // Try to enrich with AI if needed
            List<String> suggestedMarkers = rx.suggestedMarkers();
            String description = rx.description();

            if ((suggestedMarkers == null || suggestedMarkers.isEmpty()) || description == null) {
                progress.accept("enriching", "Getting additional details...");
                try {
                    DrugLookupResult aiResult = aiLookupService.lookup(query);
                    if (aiResult != null) {
                        if (suggestedMarkers == null || suggestedMarkers.isEmpty()) {
                            suggestedMarkers = aiResult.suggestedMarkers();
                        }
                        if (description == null) {
                            description = aiResult.description();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("AI enrichment failed (non-fatal): " + e.getMessage());
                }
            }

            // Create drug immediately without waiting for image
            // Image will be generated async in background
            return Optional.of(createDrugFromRxNorm(rx, suggestedMarkers, description));
        }

        // RxNorm failed - fall back to AI lookup (Gemini with Google Search)
        progress.accept("searching_ai", "Searching with AI...");
        try {
            DrugLookupResult result = aiLookupService.lookup(query);
            if (result == null) {
                return Optional.empty();
            }

            progress.accept("found", "Found: " + result.name());

            // Check again by canonical name (in case user searched by alias)
            existing = drugs.search(result.name());
            if (!existing.isEmpty()) {
                return Optional.of(existing.get(0));
            }

            // Create drug immediately without waiting for image
            // Image will be generated async in background
            return Optional.of(createDrugFromAiResult(result));
        } catch (Exception e) {
            System.err.println("AI lookup failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    private Drug createDrugFromRxNorm(RxNormDrugResult rx, List<String> suggestedMarkers, String description) {
        String drugId = UUID.randomUUID().toString();
        String fallbackUrl = DrugImageGenerator.getFallbackUrl(rx.form(), bucket);

        Drug drug = new Drug(
            drugId,
            rx.name(),
            rx.aliases() != null ? rx.aliases() : List.of(),
            parseCategory(rx.category()),
            parseForm(rx.form()),
            rx.defaultUnit() != null ? rx.defaultUnit() : "mg",
            rx.commonDoses() != null ? rx.commonDoses() : List.of(),
            null,  // imageUrl - will be set async
            fallbackUrl,
            suggestedMarkers != null ? suggestedMarkers : List.of(),
            description,
            Instant.now(),
            Instant.now(),
            null   // aliasOfDrugId
        );

        drugs.save(drug);
        generateImageAsync(drugId, rx.rxcui(), rx.name(), rx.form());
        return drug;
    }

    private Drug createDrugFromAiResult(DrugLookupResult result) {
        String drugId = UUID.randomUUID().toString();
        String fallbackUrl = DrugImageGenerator.getFallbackUrl(result.form(), bucket);

        Drug drug = new Drug(
            drugId,
            result.name(),
            result.aliases() != null ? result.aliases() : List.of(),
            parseCategory(result.category()),
            parseForm(result.form()),
            result.defaultUnit() != null ? result.defaultUnit() : "mg",
            result.commonDoses() != null ? result.commonDoses() : List.of(),
            null,  // imageUrl - will be set async
            fallbackUrl,
            result.suggestedMarkers() != null ? result.suggestedMarkers() : List.of(),
            result.description(),
            Instant.now(),
            Instant.now(),
            null   // aliasOfDrugId
        );

        drugs.save(drug);
        // AI results don't have rxcui, so pass null
        generateImageAsync(drugId, null, result.name(), result.form());
        return drug;
    }

    /**
     * Get a drug by ID.
     */
    public Optional<Drug> findById(String drugId) {
        return drugs.findById(drugId);
    }

    /**
     * Get all drugs in the catalog.
     */
    public List<Drug> findAll() {
        return drugs.findAll();
    }

    /**
     * Manually trigger image generation for a drug (async).
     */
    public void regenerateImage(String drugId) {
        Drug drug = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));
        generateImageAsync(drugId, null, drug.name(), drug.form().name());
    }

    /**
     * Manually trigger image generation for a drug (sync, for SSE).
     * @return The new image URL, or null if generation failed
     */
    public String regenerateImageSync(String drugId) {
        Drug drug = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));

        try {
            // Fetch visual info for enhanced image generation
            DrugVisualInfo visualInfo = visualLookupService.lookup(null, drug.name());

            Optional<byte[]> imageBytes;
            if (visualInfo.hasVisualCharacteristics()) {
                imageBytes = imageGenerator.generate(visualInfo);
            } else {
                imageBytes = imageGenerator.generate(drug.name(), drug.form().name());
            }

            if (imageBytes.isPresent()) {
                // Capture the previous URL before we overwrite; we'll
                // best-effort delete that GCS object AFTER the new URL
                // is durably persisted to Firestore.
                String oldUrl = drug.imageUrl();
                String imageUrl = imageStorage.upload(drugId, imageBytes.get());

                // Update drug with image URL
                Drug updated = new Drug(
                    drug.drugId(),
                    drug.name(),
                    drug.aliases(),
                    drug.category(),
                    drug.form(),
                    drug.defaultUnit(),
                    drug.commonDoses(),
                    imageUrl,
                    drug.imageFallback(),
                    drug.suggestedMarkers(),
                    drug.description(),
                    drug.createdAt(),
                    Instant.now(),
                    drug.aliasOfDrugId()
                );
                drugs.save(updated);
                // Fire-and-forget cleanup of the prior object. Order
                // matters: persist new URL first, then delete old.
                if (oldUrl != null && !oldUrl.equals(imageUrl)) {
                    try {
                        imageStorage.deleteByUrl(oldUrl);
                    } catch (Exception cleanupErr) {
                        System.err.println("Stale drug image cleanup failed for " + drugId + ": " + cleanupErr.getMessage());
                    }
                }
                return imageUrl;
            }
        } catch (Exception e) {
            System.err.println("Failed to generate image for drug " + drugId + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Generate and upload drug image asynchronously.
     * Fetches visual characteristics from RxImageAccess, OpenFDA, and DailyMed
     * to inform accurate image generation.
     *
     * @param drugId The drug ID for storage
     * @param rxcui The RxNorm CUI (may be null for AI-created drugs)
     * @param drugName The drug name
     * @param form The drug form (TABLET, CAPSULE, etc.)
     */
    private void generateImageAsync(String drugId, String rxcui, String drugName, String form) {
        CompletableFuture.runAsync(() -> {
            try {
                // First, try to get a real image from RxImageAccess
                // Also fetch visual characteristics from all sources
                DrugVisualInfo visualInfo = visualLookupService.lookup(rxcui, drugName);

                // If we have a real image URL from NIH, we could download and use it
                // For now, we generate images but informed by real characteristics
                // Future enhancement: fetch real image if visualInfo.realImageUrl() exists

                Optional<byte[]> imageBytes;

                if (visualInfo.hasVisualCharacteristics()) {
                    // Enhanced generation with actual drug appearance data
                    System.out.println("Generating image for " + drugName + " with visual data: " +
                                       visualInfo.toPromptDescription());
                    imageBytes = imageGenerator.generate(visualInfo);
                } else {
                    // Fallback to form-based generic generation
                    System.out.println("Generating generic image for " + drugName + " (no visual data available)");
                    imageBytes = imageGenerator.generate(drugName, form);
                }

                if (imageBytes.isPresent()) {
                    String imageUrl = imageStorage.upload(drugId, imageBytes.get());

                    // Update drug with image URL
                    drugs.findById(drugId).ifPresent(drug -> {
                        // Capture old URL (may be null for first-time
                        // generation) before we overwrite.
                        String oldUrl = drug.imageUrl();
                        Drug updated = new Drug(
                            drug.drugId(),
                            drug.name(),
                            drug.aliases(),
                            drug.category(),
                            drug.form(),
                            drug.defaultUnit(),
                            drug.commonDoses(),
                            imageUrl,
                            drug.imageFallback(),
                            drug.suggestedMarkers(),
                            drug.description(),
                            drug.createdAt(),
                            Instant.now(),
                            drug.aliasOfDrugId()
                        );
                        drugs.save(updated);
                        System.out.println("Image generated and saved for " + drugName);
                        // Best-effort cleanup of the prior object.
                        if (oldUrl != null && !oldUrl.equals(imageUrl)) {
                            try {
                                imageStorage.deleteByUrl(oldUrl);
                            } catch (Exception cleanupErr) {
                                System.err.println("Stale drug image cleanup failed for " + drugId + ": " + cleanupErr.getMessage());
                            }
                        }
                    });
                }
            } catch (Exception e) {
                System.err.println("Failed to generate image for drug " + drugId + ": " + e.getMessage());
            }
        });
    }

    /**
     * Admin patch — updates only the editable fields. Pass {@code null} to
     * keep the existing value for any field.
     */
    public Drug updateDrug(
        String drugId,
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit
    ) {
        Drug existing = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));

        Drug updated = new Drug(
            existing.drugId(),
            name != null ? name : existing.name(),
            aliases != null ? aliases : existing.aliases(),
            category != null ? category : existing.category(),
            form != null ? form : existing.form(),
            defaultUnit != null ? defaultUnit : existing.defaultUnit(),
            existing.commonDoses(),
            existing.imageUrl(),
            existing.imageFallback(),
            existing.suggestedMarkers(),
            existing.description(),
            existing.createdAt(),
            Instant.now(),
            existing.aliasOfDrugId()
        );
        drugs.save(updated);
        return updated;
    }

    /**
     * Admin delete — outright removes a drug from the catalog. Should be
     * blocked by the controller when any user medication still references
     * it; merge is the safer path for duplicate cleanup.
     */
    public void delete(String drugId) {
        drugs.delete(drugId);
    }

    /**
     * Number of user-medication records that still point at this drug.
     * Used by the admin delete check.
     */
    public int referencingMedicationCount(String drugId) {
        return medications.findAllReferencingDrug(drugId).size();
    }

    /**
     * Merge {@code sourceId} into {@code targetId} — admin only.
     *
     * <p>Marks the source as an alias of the target and rewrites every
     * Medication.drugId reference from source to target across all users.
     */
    public Drug mergeInto(String sourceId, String targetId) {
        if (sourceId == null || targetId == null) {
            throw new IllegalArgumentException("source and target drug ids are required");
        }
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("source and target must differ");
        }
        Drug source = drugs.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Source drug not found: " + sourceId));
        Drug target = drugs.findById(targetId)
            .orElseThrow(() -> new IllegalArgumentException("Target drug not found: " + targetId));
        if (target.aliasOfDrugId() != null) {
            throw new IllegalArgumentException("Target is itself an alias — cannot merge into an alias");
        }
        if (source.aliasOfDrugId() != null) {
            throw new IllegalArgumentException("Source is already an alias");
        }

        List<Medication> referencing = medications.findAllReferencingDrug(sourceId);
        for (Medication m : referencing) {
            Medication rewritten = new Medication(
                m.userId(),
                m.medicationId(),
                targetId,
                m.customName(),
                m.status(),
                m.dose(),
                m.unit(),
                m.frequency(),
                m.timeSlots(),
                m.protocolId(),
                m.notes(),
                m.prescribedBy(),
                m.startDate(),
                m.endDate(),
                m.discontinueReason(),
                m.discontinueNotes(),
                m.correlatedMarkers(),
                m.dosagePeriods(),
                m.createdAt(),
                Instant.now()
            );
            medications.save(rewritten);
        }

        Drug merged = new Drug(
            source.drugId(),
            source.name(),
            source.aliases(),
            source.category(),
            source.form(),
            source.defaultUnit(),
            source.commonDoses(),
            source.imageUrl(),
            source.imageFallback(),
            source.suggestedMarkers(),
            source.description(),
            source.createdAt(),
            Instant.now(),
            targetId
        );
        drugs.save(merged);

        return drugs.findById(targetId).orElse(target);
    }

    /**
     * Default prompt the image generator would use for this drug. Used by
     * the admin UI to seed an editable prompt field.
     */
    public String defaultImagePrompt(String drugId) {
        Drug drug = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));
        return imageGenerator.defaultPromptFor(drug);
    }

    /**
     * Regenerate using an admin-supplied prompt. Pass an empty/null prompt
     * to fall back to {@link #defaultImagePrompt(String)} behavior.
     */
    public String regenerateImageWithPrompt(String drugId, String promptOverride) {
        Drug drug = drugs.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));
        String prompt = (promptOverride != null && !promptOverride.isBlank())
            ? promptOverride
            : imageGenerator.defaultPromptFor(drug);
        Optional<byte[]> bytes = imageGenerator.generateWithPrompt(prompt, drug.name());
        if (bytes.isEmpty()) return null;
        // Capture old URL before overwrite for best-effort cleanup after persist.
        String oldUrl = drug.imageUrl();
        String imageUrl = imageStorage.upload(drugId, bytes.get());
        Drug updated = new Drug(
            drug.drugId(),
            drug.name(),
            drug.aliases(),
            drug.category(),
            drug.form(),
            drug.defaultUnit(),
            drug.commonDoses(),
            imageUrl,
            drug.imageFallback(),
            drug.suggestedMarkers(),
            drug.description(),
            drug.createdAt(),
            Instant.now(),
            drug.aliasOfDrugId()
        );
        drugs.save(updated);
        if (oldUrl != null && !oldUrl.equals(imageUrl)) {
            try {
                imageStorage.deleteByUrl(oldUrl);
            } catch (Exception cleanupErr) {
                System.err.println("Stale drug image cleanup failed for " + drugId + ": " + cleanupErr.getMessage());
            }
        }
        return imageUrl;
    }

    private DrugCategory parseCategory(String category) {
        if (category == null) return DrugCategory.SUPPLEMENT;
        try {
            return DrugCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return DrugCategory.SUPPLEMENT;
        }
    }

    private DrugForm parseForm(String form) {
        if (form == null) return DrugForm.TABLET;
        try {
            return DrugForm.valueOf(form);
        } catch (IllegalArgumentException e) {
            return DrugForm.TABLET;
        }
    }
}
