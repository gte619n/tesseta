package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.equipment.EquipmentResponse;
import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import java.util.List;
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

    public AdminEquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    @GetMapping("/pending")
    public List<PendingEquipmentResponse> listPending() {
        List<Equipment> pending = equipmentService.findPendingSubmissions();
        return pending.stream()
            .map(eq -> new PendingEquipmentResponse(
                eq.equipmentId(),
                eq.name(),
                eq.category(),
                eq.subcategory(),
                eq.specSchema(),
                eq.specs(),
                eq.contributorId(),
                eq.contributorId(), // TODO: lookup actual email from user service
                eq.createdAt()
            ))
            .toList();
    }

    @PostMapping("/{equipmentId}/approve")
    public EquipmentResponse approve(@PathVariable String equipmentId) {
        Equipment equipment = equipmentService.approve(equipmentId);

        // TODO: Async image generation will be added in Phase 4 (EquipmentImageService)
        // For now, just return the approved equipment

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

    @PostMapping("/{equipmentId}/regenerate-image")
    public void regenerateImage(@PathVariable String equipmentId) {
        // TODO: Will be implemented in Phase 4 with EquipmentImageService
        equipmentService.updateImageStatus(equipmentId, ImageStatus.PENDING, null);
    }
}
