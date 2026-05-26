package com.gte619n.healthfitness.core.equipment;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final LocationRepository locationRepository;
    private final Optional<EquipmentImageGenerator> imageGenerator;

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

    public EquipmentService(
        EquipmentRepository equipmentRepository,
        LocationRepository locationRepository,
        Optional<EquipmentImageGenerator> imageGenerator
    ) {
        this.equipmentRepository = equipmentRepository;
        this.locationRepository = locationRepository;
        this.imageGenerator = imageGenerator;
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
            now,
            null // aliasOfEquipmentId
        );

        equipmentRepository.save(equipment);
        imageGenerator.ifPresent(g -> g.generateImageAsync(equipment));
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
            Instant.now(),
            equipment.aliasOfEquipmentId()
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
            Instant.now(),
            equipment.aliasOfEquipmentId()
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
            Instant.now(),
            equipment.aliasOfEquipmentId()
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
            Instant.now(),
            equipment.aliasOfEquipmentId()
        );

        equipmentRepository.save(updated);
    }

    /**
     * Merge {@code sourceId} into {@code targetId} — admin only.
     *
     * <p>Marks the source as an alias of the target and rewrites every
     * Location.equipmentIds reference from source to target. Per-location
     * specs keyed by source are migrated to be keyed by target (target
     * wins on collisions). The source is also marked REJECTED so it is
     * filtered out of pending lists.
     *
     * @throws IllegalArgumentException if either id is missing, source ==
     *   target, the target is itself an alias, or either equipment doesn't
     *   exist.
     */
    public Equipment mergeInto(String sourceId, String targetId) {
        if (sourceId == null || targetId == null) {
            throw new IllegalArgumentException("source and target equipment ids are required");
        }
        if (sourceId.equals(targetId)) {
            throw new IllegalArgumentException("source and target must differ");
        }
        Equipment source = equipmentRepository.findById(sourceId)
            .orElseThrow(() -> new IllegalArgumentException("Source equipment not found: " + sourceId));
        Equipment target = equipmentRepository.findById(targetId)
            .orElseThrow(() -> new IllegalArgumentException("Target equipment not found: " + targetId));
        if (target.aliasOfEquipmentId() != null) {
            throw new IllegalArgumentException("Target is itself an alias — cannot merge into an alias");
        }
        if (source.aliasOfEquipmentId() != null) {
            throw new IllegalArgumentException("Source is already an alias");
        }

        // Rewrite every Location.equipmentIds reference from source -> target,
        // migrating per-location specs from the source key to the target key.
        List<Location> referencing = locationRepository.findAllReferencing(sourceId);
        for (Location loc : referencing) {
            List<String> ids = new ArrayList<>(loc.equipmentIds() == null ? List.of() : loc.equipmentIds());
            ids.remove(sourceId);
            if (!ids.contains(targetId)) {
                ids.add(targetId);
            }

            Map<String, Map<String, Object>> specs = new HashMap<>(
                loc.equipmentSpecs() == null ? Map.of() : loc.equipmentSpecs()
            );
            Map<String, Object> sourceSpecs = specs.remove(sourceId);
            if (sourceSpecs != null && !specs.containsKey(targetId)) {
                specs.put(targetId, sourceSpecs);
            }

            Location rewritten = new Location(
                loc.userId(),
                loc.locationId(),
                loc.name(),
                loc.address(),
                loc.coverPhotoUrl(),
                loc.is24Hours(),
                loc.hours(),
                loc.amenities(),
                ids,
                specs,
                loc.isDefault(),
                loc.isActive(),
                loc.createdAt(),
                Instant.now()
            );
            locationRepository.save(rewritten);
        }

        Equipment merged = new Equipment(
            source.equipmentId(),
            source.name(),
            source.category(),
            source.subcategory(),
            source.specSchema(),
            source.specs(),
            source.imageUrl(),
            source.imageStatus(),
            source.ownerId(),
            EquipmentStatus.REJECTED,
            source.contributorId(),
            source.exerciseCount(),
            source.createdAt(),
            Instant.now(),
            targetId
        );
        equipmentRepository.save(merged);

        return equipmentRepository.findById(targetId).orElse(target);
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
