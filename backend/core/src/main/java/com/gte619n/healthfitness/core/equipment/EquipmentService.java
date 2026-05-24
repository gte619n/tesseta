package com.gte619n.healthfitness.core.equipment;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;

    // Hardcoded category taxonomy from spec
    private static final Map<String, List<String>> CATEGORY_TREE = Map.of(
        "Free Weights", List.of("Barbells", "Dumbbells", "Kettlebells", "Weight Plates", "Other"),
        "Machines - Strength", List.of("Chest", "Back", "Shoulders", "Arms", "Legs", "Core"),
        "Machines - Cardio", List.of("Treadmill", "Elliptical", "Stationary Bike", "Rowing Machine", "Stair Climber", "Other"),
        "Cable Systems", List.of("Single Cable", "Dual Cable", "Multi-Station"),
        "Benches & Racks", List.of("Benches", "Racks", "Stations"),
        "Bodyweight", List.of("Pull-Up", "Dip", "Other"),
        "Accessories", List.of("Supports", "Attachments", "Mobility")
    );

    public EquipmentService(EquipmentRepository equipmentRepository) {
        this.equipmentRepository = equipmentRepository;
    }

    /**
     * List catalog equipment (ownerId=null, status=ACTIVE) with optional filters
     */
    public List<Equipment> listCatalog(String search, String category, String subcategory) {
        return equipmentRepository.findCatalog(search, category, subcategory);
    }

    /**
     * Get a single equipment by ID
     */
    public Optional<Equipment> findById(String equipmentId) {
        return equipmentRepository.findById(equipmentId);
    }

    /**
     * Get the category/subcategory tree
     */
    public Map<String, List<String>> getCategoryTree() {
        return CATEGORY_TREE;
    }

    /**
     * Submit new equipment (creates with ownerId=userId, status=PENDING_REVIEW, imageStatus=PENDING)
     */
    public Equipment submitEquipment(
        String userId,
        String name,
        String category,
        String subcategory,
        SpecSchema specSchema,
        Map<String, Object> specs
    ) {
        // Validate category and subcategory
        validateCategoryAndSubcategory(category, subcategory);

        String equipmentId = "eq_" + UUID.randomUUID().toString().substring(0, 12);
        Instant now = Instant.now();

        Equipment equipment = new Equipment(
            equipmentId,
            name,
            category,
            subcategory,
            specSchema,
            specs != null ? specs : Map.of(),
            null, // imageUrl - will be set when image is generated
            ImageStatus.PENDING,
            userId, // ownerId set to userId for user submissions
            EquipmentStatus.PENDING_REVIEW,
            userId, // contributorId
            0, // exerciseCount starts at 0
            now,
            now
        );

        equipmentRepository.save(equipment);
        return equipment;
    }

    /**
     * List user's submitted equipment
     */
    public List<Equipment> listUserEquipment(String userId) {
        return equipmentRepository.findByOwner(userId);
    }

    /**
     * Delete own submission (only if status=PENDING_REVIEW)
     */
    public void deleteUserSubmission(String userId, String equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found"));

        if (!userId.equals(equipment.ownerId())) {
            throw new IllegalArgumentException("Not authorized to delete this equipment");
        }

        if (equipment.status() != EquipmentStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Can only delete equipment with PENDING_REVIEW status");
        }

        equipmentRepository.delete(equipmentId);
    }

    /**
     * Find all equipment with status PENDING_REVIEW
     */
    public List<Equipment> findPendingSubmissions() {
        return equipmentRepository.findPendingReview();
    }

    /**
     * Approve equipment submission - sets status to ACTIVE and clears ownerId
     */
    public Equipment approve(String equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        if (equipment.status() != EquipmentStatus.PENDING_REVIEW) {
            throw new IllegalArgumentException("Can only approve equipment with PENDING_REVIEW status");
        }

        Equipment approved = new Equipment(
            equipment.equipmentId(),
            equipment.name(),
            equipment.category(),
            equipment.subcategory(),
            equipment.specSchema(),
            equipment.specs(),
            equipment.imageUrl(),
            equipment.imageStatus(),
            null, // Clear ownerId to promote to catalog
            EquipmentStatus.ACTIVE,
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            Instant.now()
        );

        equipmentRepository.save(approved);
        return approved;
    }

    /**
     * Reject equipment submission
     */
    public Equipment reject(String equipmentId, String reason) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        Equipment rejected = new Equipment(
            equipment.equipmentId(),
            equipment.name(),
            equipment.category(),
            equipment.subcategory(),
            equipment.specSchema(),
            equipment.specs(),
            equipment.imageUrl(),
            equipment.imageStatus(),
            equipment.ownerId(),
            EquipmentStatus.REJECTED,
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            Instant.now()
        );

        equipmentRepository.save(rejected);
        return rejected;
    }

    /**
     * Update equipment details (admin only)
     */
    public Equipment updateEquipment(
        String equipmentId,
        String name,
        String category,
        String subcategory,
        SpecSchema specSchema,
        Map<String, Object> specs
    ) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        // Validate category/subcategory if being changed
        String newCategory = category != null ? category : equipment.category();
        String newSubcategory = subcategory != null ? subcategory : equipment.subcategory();

        if (category != null || subcategory != null) {
            validateCategoryAndSubcategory(newCategory, newSubcategory);
        }

        Equipment updated = new Equipment(
            equipment.equipmentId(),
            name != null ? name : equipment.name(),
            newCategory,
            newSubcategory,
            specSchema != null ? specSchema : equipment.specSchema(),
            specs != null ? specs : equipment.specs(),
            equipment.imageUrl(),
            equipment.imageStatus(),
            equipment.ownerId(),
            equipment.status(),
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            Instant.now()
        );

        equipmentRepository.save(updated);
        return updated;
    }

    /**
     * Update image status (used by image generation service)
     */
    public void updateImageStatus(String equipmentId, ImageStatus imageStatus, String imageUrl) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
            .orElseThrow(() -> new IllegalArgumentException("Equipment not found: " + equipmentId));

        Equipment updated = new Equipment(
            equipment.equipmentId(),
            equipment.name(),
            equipment.category(),
            equipment.subcategory(),
            equipment.specSchema(),
            equipment.specs(),
            imageUrl,
            imageStatus,
            equipment.ownerId(),
            equipment.status(),
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            Instant.now()
        );

        equipmentRepository.save(updated);
    }

    /**
     * Validate that category exists and subcategory belongs to it
     */
    private void validateCategoryAndSubcategory(String category, String subcategory) {
        List<String> validSubcategories = CATEGORY_TREE.get(category);
        if (validSubcategories == null) {
            throw new IllegalArgumentException("Invalid category: " + category);
        }
        if (!validSubcategories.contains(subcategory)) {
            throw new IllegalArgumentException(
                "Invalid subcategory '" + subcategory + "' for category '" + category + "'"
            );
        }
    }
}
