package com.gte619n.healthfitness.app.jobs;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogRepository;
import com.gte619n.healthfitness.integrations.nutrition.OpenFoodFactsDumpParser;
import com.gte619n.healthfitness.integrations.nutrition.UsdaFoodParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Open-data seeding Cloud Run Job (IMPL-13 Milestone 2, ADR-0006). Imports the
 * USDA (CC0, generics) and Open Food Facts (ODbL, packaged) datasets into the
 * top-level {@code foodCatalog} collection.
 *
 * <p>Activation mirrors {@link ReevaluateSustainedJob}: this component only
 * loads under the Spring profile {@code job-seed-foods}. The deployed Cloud Run
 * Job sets {@code SPRING_PROFILES_ACTIVE=job-seed-foods}; the web service does
 * not, so the {@link CommandLineRunner} fires only inside the job execution.
 * Returning normally from {@link #run} lets Spring shut the context down and the
 * JVM exit 0 — we deliberately do not call {@code System.exit}. See
 * {@code infra/scripts/deploy-seed-foods-job.sh}.
 *
 * <p>Sources are configurable so the job is testable and never hardcodes the
 * full multi-GB downloads:
 * <ul>
 *   <li>{@code app.nutrition.seed.usda-path} — flattened USDA CSV</li>
 *   <li>{@code app.nutrition.seed.off-path} — Open Food Facts JSONL dump</li>
 * </ul>
 * Each may be a local file path or an {@code http(s)} URL, optionally
 * gzip-compressed ({@code .gz}). An unset path skips that source with a log
 * line. Upserts are idempotent: parsers assign deterministic ids
 * ({@code "usda-"+fdcId}, {@code "off-"+barcode}) and the repository
 * {@code save} merges, so re-running re-imports cleanly.
 */
@Component
@Profile("job-seed-foods")
public class SeedFoodCatalogJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(SeedFoodCatalogJob.class.getName());

    private final FoodCatalogRepository repository;
    private final UsdaFoodParser usdaParser;
    private final OpenFoodFactsDumpParser offParser;
    private final String usdaPath;
    private final String offPath;

    public SeedFoodCatalogJob(
        FoodCatalogRepository repository,
        UsdaFoodParser usdaParser,
        OpenFoodFactsDumpParser offParser,
        @Value("${app.nutrition.seed.usda-path:}") String usdaPath,
        @Value("${app.nutrition.seed.off-path:}") String offPath
    ) {
        this.repository = repository;
        this.usdaParser = usdaParser;
        this.offParser = offParser;
        this.usdaPath = usdaPath;
        this.offPath = offPath;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO, "SeedFoodCatalogJob: starting");

        long usda = importSource("USDA", usdaPath,
            reader -> usdaParser.parse(reader, saver()));
        long off = importSource("OpenFoodFacts", offPath,
            reader -> offParser.parse(reader, saver()));

        log.log(System.Logger.Level.INFO,
            "SeedFoodCatalogJob: done (usda={0}, off={1}, total={2})",
            usda, off, usda + off);
        // CommandLineRunner returns normally — Spring shutdown closes the
        // context and the JVM exits 0.
    }

    private Consumer<CatalogFood> saver() {
        return repository::save;
    }

    private long importSource(String label, String path, Function<BufferedReader, Long> work) {
        if (path == null || path.isBlank()) {
            log.log(System.Logger.Level.INFO,
                "SeedFoodCatalogJob: {0} source path unset — skipping", label);
            return 0;
        }
        log.log(System.Logger.Level.INFO,
            "SeedFoodCatalogJob: importing {0} from {1}", label, path);
        try (BufferedReader reader = open(path)) {
            long count = work.apply(reader);
            log.log(System.Logger.Level.INFO,
                "SeedFoodCatalogJob: {0} imported {1} foods", label, count);
            return count;
        } catch (Exception e) {
            log.log(System.Logger.Level.ERROR,
                "SeedFoodCatalogJob: " + label + " import failed: " + e.getMessage());
            return 0;
        }
    }

    /** Open a local file path or an http(s) URL, transparently un-gzipping {@code .gz}. */
    private static BufferedReader open(String path) throws Exception {
        InputStream raw;
        if (path.startsWith("http://") || path.startsWith("https://")) {
            URL url = URI.create(path).toURL();
            raw = url.openStream();
        } else {
            raw = Files.newInputStream(Path.of(path));
        }
        InputStream in = path.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
}
