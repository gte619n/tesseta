package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService equipmentService;

    public EquipmentController(EquipmentService equipmentService) {
        this.equipmentService = equipmentService;
    }

    /**
     * List catalog equipment (ownerId=null, status=ACTIVE)
     * Supports optional filters: search, category, subcategory
     */
    @GetMapping
    public List<EquipmentResponse> listCatalog(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String subcategory
    ) {
        return equipmentService.listCatalog(search, category, subcategory)
            .stream()
            .map(EquipmentResponse::from)
            .toList();
    }

    /**
     * Get single equipment by ID
     */
    @GetMapping("/{equipmentId}")
    public ResponseEntity<EquipmentResponse> getById(@PathVariable String equipmentId) {
        return equipmentService.findById(equipmentId)
            .map(EquipmentResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get category/subcategory tree
     */
    @GetMapping("/categories")
    public CategoryTreeResponse getCategories() {
        return new CategoryTreeResponse(equipmentService.getCategoryTree());
    }
}
