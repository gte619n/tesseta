package com.gte619n.healthfitness.app.jobs;

import com.gte619n.healthfitness.core.nutrition.FoodImageService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Studio-image backfill Cloud Run Job (IMPL-13 Milestone 4). Seeded foods are
 * imported without images ({@code imageStatus = NONE}); this job enqueues async
 * studio-image generation for every such food so the catalog gets images
 * without generating millions up front at seed time.
 *
 * <p>Activation mirrors {@link SeedFoodCatalogJob}: the component only loads
 * under the Spring profile {@code job-image-backfill}. The deployed Cloud Run
 * Job sets {@code SPRING_PROFILES_ACTIVE=job-image-backfill}; the web service
 * does not, so the {@link CommandLineRunner} fires only inside the job
 * execution. Returning normally lets Spring shut the context down and the JVM
 * exit 0.
 *
 * <p>{@link FoodImageService#backfillMissing()} enqueues generation for the
 * foods currently at {@code NONE} (paged/limited); re-running the job picks up
 * any that have not yet transitioned, so repeated runs converge.
 */
@Component
@Profile("job-image-backfill")
public class BackfillFoodImagesJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(BackfillFoodImagesJob.class.getName());

    private final FoodImageService foodImages;

    public BackfillFoodImagesJob(FoodImageService foodImages) {
        this.foodImages = foodImages;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO, "BackfillFoodImagesJob: starting");
        int enqueued = foodImages.backfillMissing();
        log.log(System.Logger.Level.INFO,
            "BackfillFoodImagesJob: enqueued {0} food(s) for studio-image generation", enqueued);
        // CommandLineRunner returns normally — Spring shutdown closes the
        // context and the JVM exits 0.
    }
}
