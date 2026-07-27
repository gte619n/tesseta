package com.gte619n.healthfitness.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

// Guards the scope wiring (ADR-0020, D11): every /v1 controller must be gated by
// @PreAuthorize on the matching SCOPE_ authority. A refactor that drops or
// mistypes a scope annotation would silently widen third-party access; this
// fails the build instead.
class V1ScopeEnforcementTest {

    @Test
    void userControllerRequiresProfileRead() {
        assertScope(V1UserController.class, "SCOPE_profile:read");
    }

    @Test
    void workoutsControllerRequiresWorkoutsRead() {
        assertScope(V1WorkoutsController.class, "SCOPE_workouts:read");
    }

    @Test
    void nutritionControllerRequiresNutritionRead() {
        assertScope(V1NutritionController.class, "SCOPE_nutrition:read");
    }

    @Test
    void medicationsControllerRequiresMedicationsRead() {
        assertScope(V1MedicationsController.class, "SCOPE_medications:read");
    }

    @Test
    void labsControllerRequiresLabsRead() {
        assertScope(V1LabsController.class, "SCOPE_labs:read");
    }

    private static void assertScope(Class<?> controller, String authority) {
        PreAuthorize pre = controller.getAnnotation(PreAuthorize.class);
        assertThat(pre)
            .as("%s must carry @PreAuthorize", controller.getSimpleName())
            .isNotNull();
        assertThat(pre.value()).isEqualTo("hasAuthority('" + authority + "')");
    }
}
