package com.gte619n.healthfitness.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P3.1 — status mapping round-trips (D8 map-don't-migrate). */
class CatalogStatusMappingTest {

    @Test
    void equipmentPublishedRoundTrips() {
        assertThat(CatalogStatusMapping.fromEquipment(EquipmentStatus.ACTIVE))
            .isEqualTo(CatalogStatus.PUBLISHED);
        assertThat(CatalogStatusMapping.toEquipment(CatalogStatus.PUBLISHED))
            .isEqualTo(EquipmentStatus.ACTIVE);
        assertThat(CatalogStatusMapping.fromEquipment(EquipmentStatus.PENDING_REVIEW))
            .isEqualTo(CatalogStatus.PENDING_REVIEW);
        assertThat(CatalogStatusMapping.fromEquipment(EquipmentStatus.REJECTED))
            .isEqualTo(CatalogStatus.REJECTED);
    }

    @Test
    void exercisePublishedRoundTrips() {
        assertThat(CatalogStatusMapping.fromExercise(ExerciseStatus.PUBLISHED))
            .isEqualTo(CatalogStatus.PUBLISHED);
        assertThat(CatalogStatusMapping.toExercise(CatalogStatus.PUBLISHED))
            .isEqualTo(ExerciseStatus.PUBLISHED);
        assertThat(CatalogStatusMapping.fromExercise(ExerciseStatus.DRAFT))
            .isEqualTo(CatalogStatus.PENDING_REVIEW);
    }

    @Test
    void foodVerifiedMapsToPublished() {
        assertThat(CatalogStatusMapping.fromFood(FoodStatus.VERIFIED))
            .isEqualTo(CatalogStatus.PUBLISHED);
        assertThat(CatalogStatusMapping.fromFood(FoodStatus.UNVERIFIED))
            .isEqualTo(CatalogStatus.PENDING_REVIEW);
        assertThat(CatalogStatusMapping.toFood(CatalogStatus.PUBLISHED))
            .isEqualTo(FoodStatus.VERIFIED);
        assertThat(CatalogStatusMapping.toFood(CatalogStatus.PENDING_REVIEW))
            .isEqualTo(FoodStatus.UNVERIFIED);
    }

    @Test
    void nullInputsDoNotThrow() {
        assertThat(CatalogStatusMapping.fromEquipment(null)).isEqualTo(CatalogStatus.PRIVATE);
        assertThat(CatalogStatusMapping.toEquipment(null)).isEqualTo(EquipmentStatus.PENDING_REVIEW);
        assertThat(CatalogStatusMapping.fromFood(null)).isEqualTo(CatalogStatus.PENDING_REVIEW);
    }
}
