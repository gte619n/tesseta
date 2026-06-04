package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.medication.DrugResponse;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugCategory;
import com.gte619n.healthfitness.core.medication.DrugForm;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

// Admin operations on the drug catalog. Gated by @AdminOnly.
//
// The DrugCatalogService lives in the `app` module, which `api` cannot
// depend on without inverting the layering. So this controller depends on
// a thin port (DrugCatalogPort) wired up in the app module — see
// AdminDrugAdapter in app/.../admin.
@RestController
@RequestMapping("/api/admin/drugs")
@AdminOnly
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class AdminDrugController {

    public interface DrugCatalogPort {
        List<Drug> findAll();
        Drug createDrug(String name, List<String> aliases, DrugCategory category,
                        DrugForm form, String defaultUnit, List<String> commonDoses,
                        List<String> suggestedMarkers, String description);
        Drug updateDrug(String drugId, String name, List<String> aliases,
                        DrugCategory category, DrugForm form, String defaultUnit);
        Drug mergeInto(String sourceId, String targetId);
        void delete(String drugId);
        int referencingMedicationCount(String drugId);
        String defaultImagePrompt(String drugId);
        String regenerateImageWithPrompt(String drugId, String promptOverride);
        String uploadImage(String drugId, byte[] bytes, String contentType);
        Drug selectImage(String drugId, String imageUrl);
        Drug deleteImage(String drugId, String imageUrl);
    }

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final DrugRepository drugRepository;
    private final DrugCatalogPort catalog;

    public AdminDrugController(DrugRepository drugRepository, DrugCatalogPort catalog) {
        this.drugRepository = drugRepository;
        this.catalog = catalog;
    }

    @GetMapping
    public List<DrugResponse> list() {
        return catalog.findAll().stream().map(DrugResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DrugResponse create(@RequestBody AdminCreateDrugRequest body) {
        Drug created = catalog.createDrug(
            body.name(),
            body.aliases(),
            body.category(),
            body.form(),
            body.defaultUnit(),
            body.commonDoses(),
            body.suggestedMarkers(),
            body.description()
        );
        return DrugResponse.from(created);
    }

    @PatchMapping("/{drugId}")
    public DrugResponse update(
        @PathVariable String drugId,
        @RequestBody AdminUpdateDrugRequest body
    ) {
        Drug updated = catalog.updateDrug(
            drugId,
            body.name(),
            body.aliases(),
            body.category(),
            body.form(),
            body.defaultUnit()
        );
        return DrugResponse.from(updated);
    }

    @DeleteMapping("/{drugId}")
    public Map<String, Object> delete(@PathVariable String drugId) {
        int refs = catalog.referencingMedicationCount(drugId);
        if (refs > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Drug is referenced by " + refs + " medication(s). Merge into another drug or remove the references first."
            );
        }
        catalog.delete(drugId);
        return Map.of("deleted", drugId);
    }

    @GetMapping("/{drugId}/image-prompt")
    public ImagePromptResponse imagePrompt(@PathVariable String drugId) {
        return new ImagePromptResponse(catalog.defaultImagePrompt(drugId));
    }

    @PostMapping("/{drugId}/regenerate-image")
    public DrugResponse regenerateImage(
        @PathVariable String drugId,
        @RequestBody(required = false) RegenerateImageRequest body
    ) {
        String override = body == null ? null : body.promptOverride();
        catalog.regenerateImageWithPrompt(drugId, override);
        Drug fresh = drugRepository.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));
        return DrugResponse.from(fresh);
    }

    @PostMapping(value = "/{drugId}/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DrugResponse uploadImage(
        @PathVariable String drugId,
        @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Image exceeds 10 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Expected image file");
        }

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Could not read uploaded file", e);
        }

        catalog.uploadImage(drugId, bytes, contentType);
        Drug fresh = drugRepository.findById(drugId)
            .orElseThrow(() -> new IllegalArgumentException("Drug not found: " + drugId));
        return DrugResponse.from(fresh);
    }

    @PostMapping("/{sourceId}/merge-into/{targetId}")
    public DrugResponse merge(@PathVariable String sourceId, @PathVariable String targetId) {
        Drug merged = catalog.mergeInto(sourceId, targetId);
        return DrugResponse.from(merged);
    }

    @PostMapping("/{drugId}/select-image")
    public DrugResponse selectImage(
        @PathVariable String drugId,
        @RequestBody SelectImageRequest body
    ) {
        return DrugResponse.from(catalog.selectImage(drugId, body.imageUrl()));
    }

    @PostMapping("/{drugId}/delete-image")
    public DrugResponse deleteImage(
        @PathVariable String drugId,
        @RequestBody SelectImageRequest body
    ) {
        return DrugResponse.from(catalog.deleteImage(drugId, body.imageUrl()));
    }

    public record AdminCreateDrugRequest(
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit,
        List<String> commonDoses,
        List<String> suggestedMarkers,
        String description
    ) {}

    public record AdminUpdateDrugRequest(
        String name,
        List<String> aliases,
        DrugCategory category,
        DrugForm form,
        String defaultUnit
    ) {}

    public record SelectImageRequest(String imageUrl) {}
}
