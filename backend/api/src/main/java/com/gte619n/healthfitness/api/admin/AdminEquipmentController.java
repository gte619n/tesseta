package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.equipment.CreateEquipmentRequest;
import com.gte619n.healthfitness.api.equipment.EquipmentResponse;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentImageGenerator;
import com.gte619n.healthfitness.core.equipment.EquipmentImageUploader;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

@RestController
@RequestMapping("/api/admin/equipment")
@AdminOnly
public class AdminEquipmentController {

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024; // 10 MB

    private final EquipmentService equipmentService;
    private final UserRepository userRepository;
    private final Optional<EquipmentImageGenerator> imageGenerator;
    private final Optional<EquipmentImageUploader> imageUploader;

    public AdminEquipmentController(
        EquipmentService equipmentService,
        UserRepository userRepository,
        Optional<EquipmentImageGenerator> imageGenerator,
        Optional<EquipmentImageUploader> imageUploader
    ) {
        this.equipmentService = equipmentService;
        this.userRepository = userRepository;
        this.imageGenerator = imageGenerator;
        this.imageUploader = imageUploader;
    }

    @GetMapping("/pending")
    public List<PendingEquipmentResponse> listPending() {
        List<Equipment> pending = equipmentService.findPendingSubmissions();
        return pending.stream()
            .map(this::toPendingResponse)
            .toList();
    }

    @GetMapping("/catalog")
    public List<PendingEquipmentResponse> listCatalog() {
        return equipmentService.listCatalog(null, null, null).stream()
            .map(this::toPendingResponse)
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse create(@Valid @RequestBody CreateEquipmentRequest request) {
        Equipment created = equipmentService.createCatalogEquipment(
            request.name(),
            request.category(),
            request.subcategory(),
            request.specSchema(),
            request.specs()
        );
        return EquipmentResponse.from(created);
    }

    private PendingEquipmentResponse toPendingResponse(Equipment eq) {
        User user = eq.contributorId() == null
            ? null
            : userRepository.findById(eq.contributorId()).orElse(null);
        return new PendingEquipmentResponse(
            eq.equipmentId(),
            eq.name(),
            eq.category(),
            eq.subcategory(),
            eq.specSchema(),
            eq.specs(),
            eq.imageUrl(),
            eq.imageCandidates(),
            eq.imageStatus() == null ? null : eq.imageStatus().name(),
            eq.contributorId(),
            user == null ? null : user.email(),
            user == null ? null : user.displayName(),
            eq.createdAt()
        );
    }

    @PostMapping("/{equipmentId}/approve")
    public EquipmentResponse approve(@PathVariable String equipmentId) {
        Equipment equipment = equipmentService.approve(equipmentId);
        return EquipmentResponse.from(equipment);
    }

    @PostMapping("/{equipmentId}/reject")
    public EquipmentResponse reject(
        @PathVariable String equipmentId,
        @RequestBody RejectRequest request
    ) {
        Equipment equipment = equipmentService.reject(equipmentId, request.reason());
        return EquipmentResponse.from(equipment);
    }

    @PatchMapping("/{equipmentId}")
    public EquipmentResponse edit(
        @PathVariable String equipmentId,
        @RequestBody UpdateEquipmentRequest request
    ) {
        Equipment equipment = equipmentService.updateEquipment(
            equipmentId,
            request.name(),
            request.category(),
            request.subcategory(),
            request.specSchema(),
            request.specs()
        );
        return EquipmentResponse.from(equipment);
    }

    @GetMapping("/{equipmentId}/image-prompt")
    public ImagePromptResponse imagePrompt(@PathVariable String equipmentId) {
        Equipment equipment = equipmentService.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));
        String prompt = imageGenerator
            .map(g -> g.defaultPrompt(equipment))
            .orElse("");
        return new ImagePromptResponse(prompt);
    }

    @PostMapping("/{equipmentId}/regenerate-image")
    public void regenerateImage(
        @PathVariable String equipmentId,
        @RequestBody(required = false) RegenerateImageRequest request
    ) {
        Equipment equipment = equipmentService.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));
        equipmentService.updateImageStatus(equipmentId, ImageStatus.PENDING, null);
        String override = request == null ? null : request.promptOverride();
        imageGenerator.ifPresent(g -> g.generateImageAsync(equipment, override));
    }

    @PostMapping(value = "/{equipmentId}/upload-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EquipmentResponse uploadImage(
        @PathVariable String equipmentId,
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
        if (imageUploader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Image upload is not configured");
        }

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Could not read uploaded file", e);
        }

        Equipment updated = imageUploader.get().uploadImage(equipmentId, bytes, contentType);
        return EquipmentResponse.from(updated);
    }

    @PostMapping("/{equipmentId}/select-image")
    public EquipmentResponse selectImage(
        @PathVariable String equipmentId,
        @RequestBody SelectImageRequest body
    ) {
        return EquipmentResponse.from(
            equipmentService.selectImage(equipmentId, body.imageUrl()));
    }

    @PostMapping("/{equipmentId}/delete-image")
    public EquipmentResponse deleteImage(
        @PathVariable String equipmentId,
        @RequestBody SelectImageRequest body
    ) {
        if (imageUploader.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Image upload is not configured");
        }
        return EquipmentResponse.from(
            imageUploader.get().deleteImage(equipmentId, body.imageUrl()));
    }

    public record SelectImageRequest(String imageUrl) {}

    @PostMapping("/{sourceId}/merge-into/{targetId}")
    public EquipmentResponse merge(
        @PathVariable String sourceId,
        @PathVariable String targetId
    ) {
        Equipment merged = equipmentService.mergeInto(sourceId, targetId);
        return EquipmentResponse.from(merged);
    }
}
