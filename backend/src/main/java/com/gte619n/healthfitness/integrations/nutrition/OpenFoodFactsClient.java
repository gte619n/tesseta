package com.gte619n.healthfitness.integrations.nutrition;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gte619n.healthfitness.core.nutrition.BarcodeLookup;
import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime barcode resolver backed by the Open Food Facts read API
 * ({@code GET /api/v2/product/{barcode}.json}, no key, polite User-Agent).
 *
 * <p>Implements {@link BarcodeLookup} so {@code FoodCatalogService} (in
 * {@code core}) can call it through the abstraction without {@code core}
 * depending on {@code integrations}.
 *
 * <p>Per ADR-0006 every food this client returns is tagged
 * {@code source = OPEN_FOOD_FACTS} with the barcode as {@code sourceRef}, so
 * OFF-derived (ODbL) rows stay isolated and attributable. Crowd-sourced data is
 * unverified, so {@code status = UNVERIFIED}.
 *
 * <p>This client never throws out of {@link #lookupByBarcode}: a miss or any
 * transport/parse error logs and returns {@link Optional#empty()} so the caller
 * falls through to the next resolution step.
 */
@Component
public class OpenFoodFactsClient implements BarcodeLookup {

    private static final System.Logger log =
        System.getLogger(OpenFoodFactsClient.class.getName());

    private static final String USER_AGENT =
        "HealthFitness160/1.0 (nutrition catalog)";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper json;
    private final String baseUrl;

    public OpenFoodFactsClient(
        @Value("${app.nutrition.openfoodfacts.base-url:https://world.openfoodfacts.org}")
        String baseUrl
    ) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
        this.json = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();
    }

    @Override
    public Optional<CatalogFood> lookupByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return Optional.empty();
        }
        try {
            String url = baseUrl + "/api/v2/product/" + barcode + ".json";
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.log(System.Logger.Level.DEBUG,
                    "OFF lookup non-200 for {0}: HTTP {1}", barcode, response.statusCode());
                return Optional.empty();
            }

            JsonNode root = json.readTree(response.body());
            // status == 1 means the product was found.
            if (root.path("status").asInt(0) != 1) {
                return Optional.empty();
            }
            JsonNode product = root.path("product");
            if (product.isMissingNode() || product.isNull()) {
                return Optional.empty();
            }
            return OpenFoodFactsMapper.toCatalogFood(barcode, product);
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING,
                "OFF lookup failed for " + barcode + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
