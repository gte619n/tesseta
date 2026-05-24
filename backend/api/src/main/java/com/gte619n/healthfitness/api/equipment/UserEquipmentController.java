package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/equipment")
public class UserEquipmentController {

    private final EquipmentService equipmentService;
    private final CurrentUserProvider currentUser;

    public UserEquipmentController(
        EquipmentService equipmentService,
        CurrentUserProvider currentUser
    ) {
        this.equipmentService = equipmentService;
        this.currentUser = currentUser;
    }

    /**
     * Submit new equipment (creates with ownerId=userId, status=PENDING_REVIEW)
     */
    @PostMapping
    public ResponseEntity<EquipmentResponse> submit(@Valid @RequestBody CreateEquipmentRequest request) {
        String userId = currentUser.get().userId();

        Equipment equipment = equipmentService.submitEquipment(
            userId,
            request.name(),
            request.category(),
            request.subcategory(),
            request.specSchema(),
            request.specs()
        );

        return ResponseEntity.status(201).body(EquipmentResponse.from(equipment));
    }

    /**
     * List user's submitted equipment
     */
    @GetMapping
    public List<EquipmentResponse> listOwn() {
        String userId = currentUser.get().userId();
        return equipmentService.listUserEquipment(userId)
            .stream()
            .map(EquipmentResponse::from)
            .toList();
    }

    /**
     * Delete own submission (only if status=PENDING_REVIEW)
     */
    @DeleteMapping("/{equipmentId}")
    public ResponseEntity<Void> delete(@PathVariable String equipmentId) {
        String userId = currentUser.get().userId();
        equipmentService.deleteUserSubmission(userId, equipmentId);
        return ResponseEntity.noContent().build();
    }
}
