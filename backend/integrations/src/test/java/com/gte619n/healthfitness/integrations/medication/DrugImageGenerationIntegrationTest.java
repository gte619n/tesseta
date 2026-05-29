package com.gte619n.healthfitness.integrations.medication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration test for drug visual lookup and image generation.
 *
 * Visual lookup tests (no API key):
 *   ./gradlew :integrations:test --tests "*DrugImageGenerationIntegrationTest.test*VisualLookupOnly" -i
 *
 * Image generation tests (requires GEMINI_API_KEY):
 *   GEMINI_API_KEY=your-key ./gradlew :integrations:test --tests "*DrugImageGenerationIntegrationTest.testFinasterideImageGeneration" -i
 *
 * Downloads generated images to /tmp/drug-images/
 */
class DrugImageGenerationIntegrationTest {

    private static final Path OUTPUT_DIR = Paths.get("/tmp/drug-images");

    private DrugVisualLookupService visualLookupService;

    @BeforeEach
    void setUp() throws IOException {
        // Create output directory
        Files.createDirectories(OUTPUT_DIR);

        // Visual lookup service doesn't need API key
        visualLookupService = new DrugVisualLookupService();
    }

    private DrugImageGenerator createImageGenerator() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY environment variable is required for image generation tests");
        }
        return new DrugImageGenerator(apiKey, "gemini-3.1-flash-image-preview");
    }

    // ==================== VISUAL LOOKUP TESTS (NO API KEY NEEDED) ====================

    @Test
    void testFinasterideVisualLookupOnly() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING VISUAL LOOKUP FOR: finasteride");
        System.out.println("=".repeat(80));

        DrugVisualLookupService.DrugVisualInfo visualInfo =
            visualLookupService.lookup("315246", "finasteride");

        logVisualInfo(visualInfo);
    }

    @Test
    void testTirzepatideVisualLookupOnly() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING VISUAL LOOKUP FOR: tirzepatide");
        System.out.println("=".repeat(80));

        DrugVisualLookupService.DrugVisualInfo visualInfo =
            visualLookupService.lookup("2601712", "tirzepatide");

        logVisualInfo(visualInfo);
    }

    @Test
    void testMetforminVisualLookupOnly() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING VISUAL LOOKUP FOR: metformin");
        System.out.println("=".repeat(80));

        DrugVisualLookupService.DrugVisualInfo visualInfo =
            visualLookupService.lookup("6809", "metformin");

        logVisualInfo(visualInfo);
    }

    @Test
    void testLisinoprilVisualLookupOnly() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING VISUAL LOOKUP FOR: lisinopril");
        System.out.println("=".repeat(80));

        DrugVisualLookupService.DrugVisualInfo visualInfo =
            visualLookupService.lookup("29046", "lisinopril");

        logVisualInfo(visualInfo);
    }

    // ==================== IMAGE GENERATION TESTS (REQUIRES API KEY) ====================

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testFinasterideImageGeneration() throws IOException {
        testDrugImageGeneration("finasteride", "315246");
    }

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testOmeprazoleImageGeneration() throws IOException {
        testDrugImageGeneration("omeprazole", "7646");
    }

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testSertralineImageGeneration() throws IOException {
        testDrugImageGeneration("sertraline", "36437");
    }

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testTirzepatideImageGeneration() throws IOException {
        testDrugImageGeneration("tirzepatide", "2601712");
    }

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testMetforminImageGeneration() throws IOException {
        testDrugImageGeneration("metformin", "6809");
    }

    @Test
    @Disabled("Requires GEMINI_API_KEY - run manually")
    void testLisinoprilImageGeneration() throws IOException {
        testDrugImageGeneration("lisinopril", "29046");
    }

    // ==================== HELPER METHODS ====================

    private void testDrugImageGeneration(String drugName, String rxcui) throws IOException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("TESTING IMAGE GENERATION FOR: " + drugName);
        System.out.println("=".repeat(80));

        // Step 1: Visual lookup
        System.out.println("\n--- STEP 1: Visual Lookup ---");
        DrugVisualLookupService.DrugVisualInfo visualInfo =
            visualLookupService.lookup(rxcui, drugName);

        logVisualInfo(visualInfo);

        // Step 2: Build and log the prompt
        System.out.println("\n--- STEP 2: Generated Prompt ---");
        String promptDescription = visualInfo.toPromptDescription();
        System.out.println("Subject description: " + promptDescription);
        System.out.println("\nHas visual characteristics: " + visualInfo.hasVisualCharacteristics());
        System.out.println("Has real image URL: " + (visualInfo.realImageUrl() != null));
        if (visualInfo.realImageUrl() != null) {
            System.out.println("Real image URL: " + visualInfo.realImageUrl());
        }

        // Step 3: Generate image
        System.out.println("\n--- STEP 3: Image Generation ---");
        DrugImageGenerator imageGenerator = createImageGenerator();
        Optional<byte[]> imageBytes = imageGenerator.generate(visualInfo);

        if (imageBytes.isPresent()) {
            // Save to disk
            String filename = drugName.toLowerCase().replaceAll("[^a-z0-9]", "-") + ".png";
            Path outputPath = OUTPUT_DIR.resolve(filename);
            Files.write(outputPath, imageBytes.get());

            System.out.println("SUCCESS! Image generated and saved.");
            System.out.println("\n" + "=".repeat(80));
            System.out.println("IMAGE SAVED TO: file://" + outputPath.toAbsolutePath());
            System.out.println("=".repeat(80));
        } else {
            System.out.println("FAILED: No image generated");
        }
    }

    private void logVisualInfo(DrugVisualLookupService.DrugVisualInfo info) {
        System.out.println("\nVisual Info Retrieved:");
        System.out.println("  Drug Name: " + info.drugName());
        System.out.println("  Real Image URL: " + info.realImageUrl());
        System.out.println("  Color: " + info.color());
        System.out.println("  Shape: " + info.shape());
        System.out.println("  Imprint: " + info.imprint());
        System.out.println("  Size: " + info.size());
        System.out.println("  Dosage Form: " + info.dosageForm());
        System.out.println("  Route: " + info.route());
        System.out.println("  Brand Name: " + info.brandName());
        System.out.println("  Manufacturer: " + info.manufacturer());
        System.out.println("  Physical Description: " + info.physicalDescription());
        System.out.println("  Has Visual Characteristics: " + info.hasVisualCharacteristics());
        System.out.println("\n  Prompt Description: " + info.toPromptDescription());
    }
}
