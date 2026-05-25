package com.gte619n.healthfitness.app.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentImageGenerator;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.ImageStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * One-shot batch jobs for backfilling equipment images against the LIVE
 * Firestore + GCS the dev backend uses. Not run as part of CI — gated behind
 * the {@code RUN_BATCH_REGEN=1} env var via {@link Assumptions#assumeTrue}.
 *
 * <p>Uses Application Default Credentials. Make sure
 * {@code gcloud auth application-default login} has been run (if dev.sh works
 * locally, this works too).
 *
 * <p>Boots with {@code webEnvironment = MOCK} so it does NOT bind port 8080
 * — safe to run while the dev backend is up. MOCK is needed (not NONE)
 * because SecurityConfig requires the HttpSecurity bean.
 *
 * <h2>How to run</h2>
 *
 * <h3>1. Dry run — list what WOULD be processed (no API calls, no writes)</h3>
 * <pre>
 *   RUN_BATCH_REGEN=1 \
 *   GEMINI_API_KEY=$(gcloud secrets versions access latest --secret=gemini_api_key --project=health-fitness-160) \
 *     ./gradlew :app:test \
 *     --tests "*RegenerateAllEquipmentImagesIntegrationTest.dryRunListMissing" -i
 * </pre>
 *
 * <h3>2. Live run — generate, upload, and persist for every missing item</h3>
 * Throttled to one new generation every 2 seconds to stay polite to Gemini.
 * Waits up to 30 minutes for all to complete.
 * <pre>
 *   RUN_BATCH_REGEN=1 \
 *   GEMINI_API_KEY=$(gcloud secrets versions access latest --secret=gemini_api_key --project=health-fitness-160) \
 *     ./gradlew :app:test \
 *     --tests "*RegenerateAllEquipmentImagesIntegrationTest.regenerateMissingImages" -i
 * </pre>
 *
 * Without {@code RUN_BATCH_REGEN=1} each test no-ops via
 * {@link Assumptions#assumeTrue}, so a bare {@code ./gradlew test} skips them
 * safely.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RegenerateAllEquipmentImagesIntegrationTest {

    private static final Logger log =
        LoggerFactory.getLogger(RegenerateAllEquipmentImagesIntegrationTest.class);

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private EquipmentImageGenerator imageGenerator;

    @BeforeEach
    void requireOptIn() {
        Assumptions.assumeTrue(
            "1".equals(System.getenv("RUN_BATCH_REGEN")),
            "Set RUN_BATCH_REGEN=1 to enable this manual one-shot batch test"
        );
    }

    @Test
    void dryRunListMissing() {
        List<Equipment> needsImage = findEquipmentNeedingImages();
        log.info("=".repeat(80));
        log.info("DRY RUN: {} equipment item(s) need image generation:", needsImage.size());
        log.info("=".repeat(80));
        for (Equipment e : needsImage) {
            log.info("  {} | status={} imageStatus={} | {} ({}/{})",
                e.equipmentId(), e.status(), e.imageStatus(),
                e.name(), e.category(), e.subcategory());
        }
        log.info("=".repeat(80));
        log.info("Total: {}", needsImage.size());
    }

    @Test
    void regenerateMissingImages() throws Exception {
        List<Equipment> needsImage = findEquipmentNeedingImages();
        log.info("=".repeat(80));
        log.info("REGENERATING images for {} equipment item(s)", needsImage.size());
        log.info("=".repeat(80));

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Equipment e : needsImage) {
            log.info("  -> queueing {}: {}", e.equipmentId(), e.name());
            futures.add(imageGenerator.generateImageAsync(e));
            // Throttle ~1 call every 2s to be polite to Gemini.
            Thread.sleep(2000);
        }

        log.info("All {} jobs queued. Waiting for completion (up to 30 min)...",
            futures.size());
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .get(30, TimeUnit.MINUTES);

        log.info("=".repeat(80));
        log.info("DONE. {} item(s) processed. Check Firestore for updated imageUrl/imageStatus.",
            futures.size());
        log.info("=".repeat(80));
    }

    /**
     * "Needs image" = no imageUrl OR imageStatus is not GENERATED. Covers BOTH
     * the ACTIVE catalog and PENDING_REVIEW user submissions.
     */
    private List<Equipment> findEquipmentNeedingImages() {
        List<Equipment> all = new ArrayList<>();
        all.addAll(equipmentRepository.findCatalog(null, null, null));
        all.addAll(equipmentRepository.findPendingReview());

        return all.stream()
            .filter(e -> e.imageUrl() == null
                      || e.imageUrl().isBlank()
                      || e.imageStatus() != ImageStatus.GENERATED)
            .toList();
    }
}
