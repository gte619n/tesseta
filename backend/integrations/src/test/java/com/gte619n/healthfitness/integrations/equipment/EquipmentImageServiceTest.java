package com.gte619n.healthfitness.integrations.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class EquipmentImageServiceTest {

    @Mock
    private EquipmentImageStorage storage;

    @Mock
    private EquipmentRepository equipmentRepository;

    private EquipmentImageService service;

    @BeforeEach
    void setUp() {
        // Empty API key — fine for buildPrompt tests; we never invoke
        // generateImageAsync here so the missing client never matters.
        service = new EquipmentImageService(storage, equipmentRepository, "", "gemini-3.1-flash-image-preview");
    }

    @Test
    void buildPrompt_containsEquipmentName() {
        var equipment = new Equipment(
            "eq_123",
            "Olympic Barbell",
            "Free Weights",
            "Barbells",
            SpecSchema.PLATE_LOADED,
            Map.of("barWeight", 45),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Olympic Barbell");
    }

    @Test
    void buildPrompt_containsPhotographyTreatment() {
        var equipment = new Equipment(
            "eq_123",
            "Flat Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of(),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Warm neutral");
        assertThat(prompt).contains("F0EBE0");
        assertThat(prompt).contains("isolated, centered");
        assertThat(prompt).contains("brushed steel");
        assertThat(prompt).contains("Three-quarter angle");
    }

    @Test
    void buildPrompt_includePlateLoadedSpecs() {
        var equipment = new Equipment(
            "eq_123",
            "Leg Press",
            "Machines - Strength",
            "Legs",
            SpecSchema.PLATE_LOADED,
            Map.of("barWeight", 100),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Leg Press");
        assertThat(prompt).contains("100lb bar");
    }

    @Test
    void buildPrompt_includeSelectorizedSpecs() {
        var equipment = new Equipment(
            "eq_123",
            "Lat Pulldown",
            "Machines - Strength",
            "Back",
            SpecSchema.SELECTORIZED,
            Map.of("maxWeight", 300),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Lat Pulldown");
        assertThat(prompt).contains("300lbs");
    }

    @Test
    void buildPrompt_includeCableSpecs() {
        var equipment = new Equipment(
            "eq_123",
            "Dual Cable Station",
            "Cable Systems",
            "Dual Cable",
            SpecSchema.CABLE,
            Map.of("weightStacks", 2),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Dual Cable Station");
        assertThat(prompt).contains("2 weight stacks");
    }

    @Test
    void buildPrompt_handlesMissingSpecs() {
        var equipment = new Equipment(
            "eq_123",
            "Pull-Up Bar",
            "Bodyweight",
            "Pull-Up",
            SpecSchema.BODYWEIGHT,
            null,
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("Pull-Up Bar");
        assertThat(prompt).contains("steel construction");
    }

    @Test
    void buildPrompt_includesMaterialHintForFreeWeights() {
        var equipment = new Equipment(
            "eq_123",
            "Dumbbell Set",
            "Free Weights",
            "Dumbbells",
            null,
            null,
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("iron and steel construction");
    }

    @Test
    void buildPrompt_includesMaterialHintForMachines() {
        var equipment = new Equipment(
            "eq_123",
            "Chest Press",
            "Machines - Strength",
            "Chest",
            SpecSchema.SELECTORIZED,
            Map.of(),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            null,
            EquipmentStatus.ACTIVE,
            "admin",
            0,
            Instant.now(),
            Instant.now(),
            null
        );

        String prompt = service.buildPrompt(equipment);

        assertThat(prompt).contains("steel frame with padded upholstery");
    }
}
