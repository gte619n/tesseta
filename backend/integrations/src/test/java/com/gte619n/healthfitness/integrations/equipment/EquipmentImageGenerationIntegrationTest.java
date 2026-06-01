package com.gte619n.healthfitness.integrations.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Integration test for equipment image generation via Gemini 3.1 Flash.
 *
 * Requires GEMINI_API_KEY env var. Run a single test:
 *   GEMINI_API_KEY=your-key ./gradlew :integrations:test \
 *     --tests "*EquipmentImageGenerationIntegrationTest.testDumbbellsWeightSet" -i
 *
 * Or run all tests in the class:
 *   GEMINI_API_KEY=your-key ./gradlew :integrations:test \
 *     --tests "*EquipmentImageGenerationIntegrationTest" -i
 *
 * Generated images are saved to /tmp/equipment-images/ — file:// URLs are
 * printed to the test log for click-to-open.
 */
@Disabled("Requires GEMINI_API_KEY - run manually")
class EquipmentImageGenerationIntegrationTest {

    private static final Path OUTPUT_DIR = Paths.get("/tmp/equipment-images");

    private EquipmentImageService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        service = createService();
    }

    private EquipmentImageService createService() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "GEMINI_API_KEY environment variable is required for image generation tests");
        }
        // Mock the storage + repository — this test exercises the Gemini call only.
        EquipmentImageStorage storage = Mockito.mock(EquipmentImageStorage.class);
        EquipmentRepository repository = Mockito.mock(EquipmentRepository.class);
        return new EquipmentImageService(storage, repository, apiKey, "gemini-3.1-flash-image-preview");
    }

    // ==================== TEST METHODS — ONE PER SPEC SCHEMA ====================

    @Test
    void testDumbbellsWeightSet() throws IOException {
        Equipment dumbbells = makeEquipment(
            "Dumbbells (5-100 lbs)",
            "Free Weights", "Dumbbells", SpecSchema.WEIGHT_SET,
            Map.of("minWeight", 5, "maxWeight", 100, "increment", 5)
        );
        runImageTest("dumbbells-weight-set", dumbbells);
    }

    @Test
    void testEzCurlBarbells() throws IOException {
        Equipment ezBars = makeEquipment(
            "EZ Curl Barbells",
            "Free Weights", "Barbells", SpecSchema.WEIGHT_SET,
            Map.of("weights", List.of(20, 30, 40, 50, 60, 70, 80, 90, 100, 110))
        );
        runImageTest("ez-curl-barbells", ezBars);
    }

    @Test
    void testTreadmillCardio() throws IOException {
        Equipment treadmill = makeEquipment(
            "Treadmill",
            "Machines - Cardio", "Treadmill", SpecSchema.CARDIO,
            Map.of("hasIncline", true)
        );
        runImageTest("treadmill-cardio", treadmill);
    }

    @Test
    void testLegPressSelectorized() throws IOException {
        Equipment legPress = makeEquipment(
            "Leg Press",
            "Machines - Strength", "Legs", SpecSchema.SELECTORIZED,
            Map.of("maxWeight", 400)
        );
        runImageTest("leg-press-selectorized", legPress);
    }

    @Test
    void testSquatRackPlateLoaded() throws IOException {
        Equipment squatRack = makeEquipment(
            "Power Rack with Barbell",
            "Benches & Racks", "Racks", SpecSchema.PLATE_LOADED,
            Map.of("barWeight", 45)
        );
        runImageTest("squat-rack-plate-loaded", squatRack);
    }

    @Test
    void testFunctionalTrainerCable() throws IOException {
        Equipment cable = makeEquipment(
            "Functional Trainer",
            "Cable Systems", "Dual Cable", SpecSchema.CABLE,
            Map.of("weightStacks", 2)
        );
        runImageTest("functional-trainer-cable", cable);
    }

    @Test
    void testPullUpBarBodyweight() throws IOException {
        Equipment pullUpBar = makeEquipment(
            "Pull-Up Bar",
            "Bodyweight", "Pull-Up", SpecSchema.BODYWEIGHT,
            Map.of()
        );
        runImageTest("pull-up-bar-bodyweight", pullUpBar);
    }

    // ==================== HELPERS ====================

    private void runImageTest(String filenameBase, Equipment equipment) throws IOException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING IMAGE GENERATION FOR: " + equipment.name());
        System.out.println("  Category:    " + equipment.category() + " / " + equipment.subcategory());
        System.out.println("  Spec schema: " + equipment.specSchema());
        System.out.println("  Specs:       " + equipment.specs());
        System.out.println("=".repeat(80));

        System.out.println("\n--- STEP 1: Generated Prompt ---");
        String prompt = service.buildPrompt(equipment);
        System.out.println(prompt);

        System.out.println("\n--- STEP 2: Image Generation ---");
        byte[] bytes = service.callGemini(prompt, equipment.equipmentId());

        if (bytes != null && bytes.length > 0) {
            Path outputPath = OUTPUT_DIR.resolve(filenameBase + ".png");
            Files.write(outputPath, bytes);
            System.out.println("SUCCESS! " + bytes.length + " bytes written.");
            System.out.println("\n" + "=".repeat(80));
            System.out.println("IMAGE SAVED TO: file://" + outputPath.toAbsolutePath());
            System.out.println("=".repeat(80));
        } else {
            System.out.println("FAILED: callGemini returned " + (bytes == null ? "null" : "0 bytes"));
        }
    }

    private Equipment makeEquipment(
        String name,
        String category,
        String subcategory,
        SpecSchema schema,
        Map<String, Object> specs
    ) {
        String id = "test_" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        Instant now = Instant.now();
        return new Equipment(
            id, name, category, subcategory, schema, specs,
            null,                       // imageUrl
            java.util.List.of(),        // imageCandidates
            ImageStatus.PENDING,
            null,                       // ownerId (catalog item)
            EquipmentStatus.ACTIVE,
            null,                       // contributorId
            0,                          // exerciseCount
            now, now,
            null                        // aliasOfEquipmentId
        );
    }
}
