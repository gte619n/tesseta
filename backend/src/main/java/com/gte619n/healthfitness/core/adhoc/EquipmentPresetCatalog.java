package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resolves the ad-hoc generator's equipment input (a preset, a saved gym, or an
 * explicit id set) into a concrete catalog-equipment-id set for the hard
 * constraint check (IMPL-ADHOC-01 D4/D11).
 *
 * <p>Presets map to catalog <em>categories</em> (the equipment taxonomy) which
 * are resolved to ids against the live catalog:
 * <ul>
 *   <li>{@code bodyweight} → empty (only zero-requirement exercises pass).</li>
 *   <li>{@code home} → free weights + benches/racks + bodyweight.</li>
 *   <li>{@code hotel-gym} → the above + selectorized machines + cardio + cables
 *       (a typical hotel fitness room).</li>
 *   <li>{@code full-gym} → the entire catalog.</li>
 * </ul>
 * A {@code loc_…} preset id (or a saved-gym label) resolves to that
 * {@link Location}'s own equipment. Pure free text with no preset can't be
 * mapped deterministically here (that's the AI's job) — the caller falls back to
 * the full catalog so the validator only catches unknown/unpublished exercises,
 * not legitimate free-text gear (AD-09).
 */
@Service
public class EquipmentPresetCatalog {

    /** Preset id → the catalog categories it includes. */
    private static final Map<String, List<String>> PRESET_CATEGORIES = Map.of(
        "bodyweight", List.of(),
        "home", List.of("Free Weights", "Benches & Racks", "Bodyweight"),
        "hotel-gym", List.of("Free Weights", "Benches & Racks", "Bodyweight",
            "Machines - Strength", "Machines - Cardio", "Cable Systems"),
        "full-gym", List.of("*")
    );

    public static final Set<String> KNOWN_PRESETS = PRESET_CATEGORIES.keySet();

    private final EquipmentRepository equipment;
    private final LocationRepository locations;

    public EquipmentPresetCatalog(EquipmentRepository equipment, LocationRepository locations) {
        this.equipment = equipment;
        this.locations = locations;
    }

    /**
     * Resolve the available-equipment id set for a generation request.
     *
     * @param userId       owner (for saved-gym resolution)
     * @param presetId     a known preset ({@link #KNOWN_PRESETS}) or a "loc_…"
     *                     saved-gym reference, or null
     * @param explicitIds  a caller-supplied id set (from the ad-hoc equipment
     *                     picker); wins when non-empty
     */
    public Set<String> resolve(String userId, String presetId, List<String> explicitIds) {
        if (explicitIds != null && !explicitIds.isEmpty()) {
            return new LinkedHashSet<>(explicitIds);
        }
        if (presetId == null || presetId.isBlank()) {
            return fullCatalogIds();
        }
        if ("bodyweight".equals(presetId)) {
            return Set.of();
        }
        List<String> categories = PRESET_CATEGORIES.get(presetId);
        if (categories != null) {
            if (categories.contains("*")) {
                return fullCatalogIds();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (String category : categories) {
                for (Equipment e : equipment.findCatalog(null, category, null)) {
                    ids.add(e.equipmentId());
                }
            }
            return ids;
        }
        // A saved-gym reference ("loc_…" or an exact location id).
        Location loc = locations.findById(userId, presetId).orElse(null);
        if (loc != null && loc.equipmentIds() != null) {
            return new LinkedHashSet<>(loc.equipmentIds());
        }
        // Unknown preset → be permissive; the validator still catches unknown ids.
        return fullCatalogIds();
    }

    private Set<String> fullCatalogIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Equipment e : equipment.findCatalog(null, null, null)) {
            ids.add(e.equipmentId());
        }
        return ids;
    }
}
