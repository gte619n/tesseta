package com.gte619n.healthfitness.core.exercise;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Seeds the catalog from {@link ExerciseSeedData}. Idempotent: skips exercises
 * whose name already exists. Equipment requirements are resolved from the
 * Equipment catalog by name (case-insensitive); groups that resolve to nothing
 * are dropped and reported so an admin can wire them up. Seeded exercises are
 * PUBLISHED with no media yet (mediaStatus NONE) — set
 * {@code app.exercises.require-approved-media=false} to program them before
 * media is approved, or generate + approve their demos.
 */
@Service
public class ExerciseCatalogSeeder {

    private final ExerciseService exerciseService;
    private final ExerciseRepository exercises;
    private final EquipmentRepository equipment;

    public ExerciseCatalogSeeder(
        ExerciseService exerciseService,
        ExerciseRepository exercises,
        EquipmentRepository equipment
    ) {
        this.exerciseService = exerciseService;
        this.exercises = exercises;
        this.equipment = equipment;
    }

    public record SeedResult(int seeded, int skipped, List<String> unresolvedEquipmentNames) {}

    public SeedResult seed() {
        Map<String, String> equipmentByName = new HashMap<>();
        for (Equipment e : equipment.findCatalog(null, null, null)) {
            if (e.name() != null) {
                equipmentByName.put(e.name().toLowerCase(), e.equipmentId());
            }
        }
        Set<String> existing = new HashSet<>();
        for (Exercise e : exercises.findAll()) {
            if (e.nameLower() != null) existing.add(e.nameLower());
        }

        int seeded = 0;
        int skipped = 0;
        Set<String> unresolved = new TreeSet<>();

        for (ExerciseSeed seed : ExerciseSeedData.SEEDS) {
            if (existing.contains(seed.name().toLowerCase())) {
                skipped++;
                continue;
            }
            List<EquipmentRequirement> requirements = new ArrayList<>();
            for (List<String> group : seed.requiredEquipmentNames()) {
                List<String> ids = new ArrayList<>();
                for (String name : group) {
                    String id = equipmentByName.get(name.toLowerCase());
                    if (id != null) {
                        ids.add(id);
                    } else {
                        unresolved.add(name);
                    }
                }
                if (!ids.isEmpty()) {
                    requirements.add(new EquipmentRequirement(ids));
                }
            }
            RepRange repRange = (seed.repMin() != null && seed.repMax() != null)
                ? new RepRange(seed.repMin(), seed.repMax()) : null;
            ExerciseEdit edit = new ExerciseEdit(
                seed.name(), List.of(), seed.movementPattern(), seed.primaryMuscles(),
                seed.secondaryMuscles(), seed.laterality(), seed.mechanic(), null, seed.formCues(),
                requirements, seed.suitableBlockTypes(), repRange, seed.isTimed(), null);
            Exercise created = exerciseService.create(edit, null);
            exerciseService.publish(created.exerciseId());
            seeded++;
        }

        return new SeedResult(seeded, skipped, new ArrayList<>(unresolved));
    }
}
