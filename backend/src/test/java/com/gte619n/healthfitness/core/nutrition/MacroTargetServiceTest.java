package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The macro target keeps calories consistent with the macro grams (Atwater
 * 4/4/9), so a target and the food logged against it sit on the same scale.
 */
class MacroTargetServiceTest {

    private final AtomicReference<MacroTarget> saved = new AtomicReference<>();

    private final MacroTargetRepository repository = new MacroTargetRepository() {
        @Override public Optional<MacroTarget> findActive(String userId) {
            return Optional.ofNullable(saved.get());
        }

        @Override public void save(MacroTarget target) {
            saved.set(target);
        }

        @Override public List<MacroTarget> findAll(String userId) {
            return saved.get() == null ? List.of() : List.of(saved.get());
        }
    };

    private final MacroTargetService service =
        new MacroTargetService(repository, new MetricChangedPublisher(event -> { }));

    @Test
    void setTargetDerivesCaloriesFromMacros_ignoringTheEnteredCalories() {
        // 4·180 + 4·250 + 9·80 = 2440 — the 9999 the user typed is replaced.
        MacroTarget target = service.setTarget(
            "u1", new Macros(9999.0, 180.0, 250.0, 80.0, 30.0, 40.0));

        assertEquals(2440.0, target.macros().caloriesKcal(), 1e-9);
        assertEquals(180.0, target.macros().proteinGrams(), 1e-9);
        assertEquals(250.0, target.macros().carbsGrams(), 1e-9);
        assertEquals(80.0, target.macros().fatGrams(), 1e-9);
    }

    @Test
    void caloriesOnlyTargetKeepsItsCalories() {
        // No protein/carbs/fat ⇒ nothing to derive from; the kcal target stands.
        MacroTarget target = service.setTarget(
            "u1", new Macros(1500.0, null, null, null, null, null));

        assertEquals(1500.0, target.macros().caloriesKcal(), 1e-9);
        assertNull(target.macros().proteinGrams());
    }
}
