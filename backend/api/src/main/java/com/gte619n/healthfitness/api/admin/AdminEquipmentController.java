package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.equipment.EquipmentResponse;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentImageGenerator;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/equipment")
@AdminOnly
public class AdminEquipmentController {

    private final EquipmentService equipmentService;
    private final UserRepository userRepository;
    private final Optional<EquipmentImageGenerator> imageGenerator;

    public AdminEquipmentController(
        EquipmentService equipmentService,
        UserRepository userRepository,
        Optional<EquipmentImageGenerator> imageGenerator
    ) {
        this.equipmentService = equipmentService;
        this.userRepository = userRepository;
        this.imageGenerator = imageGenerator;
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
        imageGenerator.ifPresent(g -> g.generateImageAsync(equipment));
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

    @PostMapping("/{sourceId}/merge-into/{targetId}")
    public EquipmentResponse merge(
        @PathVariable String sourceId,
        @PathVariable String targetId
    ) {
        Equipment merged = equipmentService.mergeInto(sourceId, targetId);
        return EquipmentResponse.from(merged);
    }
}
