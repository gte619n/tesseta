package com.gte619n.healthfitness.core.nutrition;

import java.util.Optional;

/**
 * Resolves a barcode (GTIN/EAN/UPC) to a {@link CatalogFood} from an external
 * source when the local catalog has no match.
 *
 * <p>Defined in {@code core} so {@link FoodCatalogService} can depend on the
 * abstraction without pulling {@code integrations} (and its outbound-HTTP
 * dependencies) into the {@code core} layer. The concrete implementation
 * ({@code OpenFoodFactsClient}) lives in {@code integrations}; per ADR-0006 it
 * tags every resolved food {@code source = OPEN_FOOD_FACTS} with the barcode as
 * its {@code sourceRef}.
 *
 * <p>Implementations must never throw out of {@link #lookupByBarcode}: a miss or
 * any transport/parse error returns {@link Optional#empty()} so the catalog
 * service can fall through (next: label-photo Gemini OCR, per the spec's
 * barcode lookup order).
 */
public interface BarcodeLookup {

    /**
     * Look up a packaged product by barcode from the external source.
     *
     * @param barcode GTIN/EAN/UPC code
     * @return the resolved food, or empty on miss or error (never throws)
     */
    Optional<CatalogFood> lookupByBarcode(String barcode);
}
