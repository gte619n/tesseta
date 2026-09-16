package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
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
    private final SyncWriteContext syncWrite;

    public UserEquipmentController(
        EquipmentService equipmentService,
        CurrentUserProvider currentUser,
        SyncWriteContext syncWrite
    ) {
        this.equipmentService = equipmentService;
        this.currentUser = currentUser;
        this.syncWrite = syncWrite;
    }

    /**
     * Submit new equipment (creates with ownerId=userId, status=PENDING_REVIEW).
     *
     * <p>Idempotent on the {@code Idempotency-Key} header: {@code submitEquipment}
     * mints an {@code "eq_…"} id server-side, so a replayed submission would
     * otherwise create a duplicate PENDING_REVIEW row. A replay returns the
     * originally-submitted equipment.
     */
    @PostMapping
    public ResponseEntity<EquipmentResponse> submit(@Valid @RequestBody CreateEquipmentRequest request) {
        String userId = currentUser.get().userId();

        EquipmentResponse response = syncWrite.idempotentCreate(
            "equipment:submit",
            userId,
            () -> {
                Equipment equipment = equipmentService.submitEquipment(
                    userId,
                    request.name(),
                    request.category(),
                    request.subcategory(),
                    request.specSchema(),
                    request.specs()
                );
                return new SyncWriteContext.Created<>(equipment.equipmentId(), EquipmentResponse.from(equipment));
            },
            equipmentId -> equipmentService.findById(equipmentId).map(EquipmentResponse::from));

        return ResponseEntity.status(201).body(response);
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
