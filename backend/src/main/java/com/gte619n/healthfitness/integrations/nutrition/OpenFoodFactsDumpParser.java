package com.gte619n.healthfitness.integrations.nutrition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import java.io.BufferedReader;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Parses the Open Food Facts JSONL dump — one product JSON object per line —
 * into {@link CatalogFood} rows for bulk seeding (IMPL-13 Milestone 2,
 * ADR-0006).
 *
 * <p>Streams line by line so a multi-GB dump never lands fully in memory: each
 * parsed product is handed to the supplied {@code consumer} (typically a
 * {@code repository::save}). Products without a barcode or a populated
 * {@code nutriments} block are skipped — the mapping is shared with the runtime
 * {@link OpenFoodFactsClient} via {@link OpenFoodFactsMapper}, so seeded rows
 * and barcode-looked-up rows are identical in shape, source tagging, and
 * deterministic {@code "off-" + barcode} id.
 *
 * <p>Unit-testable independently of Firestore and the job: callers pass any
 * {@link BufferedReader}.
 */
@Component
public class OpenFoodFactsDumpParser {

    private static final System.Logger log =
        System.getLogger(OpenFoodFactsDumpParser.class.getName());

    private final ObjectMapper json;

    public OpenFoodFactsDumpParser() {
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    /**
     * Stream a JSONL dump, emitting one {@link CatalogFood} per usable product.
     *
     * @param reader   source of newline-delimited product JSON (caller closes)
     * @param consumer receives each parsed food (e.g. {@code repository::save})
     * @return the number of foods emitted to {@code consumer}
     */
    public long parse(BufferedReader reader, Consumer<CatalogFood> consumer) {
        long emitted = 0;
        long lineNo = 0;
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                Optional<CatalogFood> food = parseLine(line);
                if (food.isPresent()) {
                    consumer.accept(food.get());
                    emitted++;
                }
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                "OFF dump parse stopped at line " + lineNo + ": " + e.getMessage());
        }
        return emitted;
    }

    /** Parse a single JSONL line; empty when unusable (bad JSON, no barcode, no nutriments). */
    Optional<CatalogFood> parseLine(String line) {
        try {
            JsonNode product = json.readTree(line);
            if (product == null || product.isMissingNode() || product.isNull()) {
                return Optional.empty();
            }
            String barcode = barcodeOf(product);
            if (barcode == null) {
                return Optional.empty();
            }
            return OpenFoodFactsMapper.toCatalogFood(barcode, product);
        } catch (Exception e) {
            // One bad line must not abort the whole dump.
            return Optional.empty();
        }
    }

    private static String barcodeOf(JsonNode product) {
        for (String field : new String[] {"code", "_id", "id"}) {
            JsonNode v = product.path(field);
            if (!v.isMissingNode() && !v.isNull()) {
                String s = v.asText(null);
                if (s != null && !s.isBlank()) {
                    return s.trim();
                }
            }
        }
        return null;
    }
}
