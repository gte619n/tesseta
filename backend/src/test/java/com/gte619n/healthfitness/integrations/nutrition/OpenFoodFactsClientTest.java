package com.gte619n.healthfitness.integrations.nutrition;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link OpenFoodFactsClient} against a local in-process HTTP stub
 * (base-url pointed at {@code http://localhost:<port>}). No live network.
 */
class OpenFoodFactsClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void stub(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test
    void resolvesFoundProduct() {
        stub("/api/v2/product/3017620422003.json", 200,
            "{\"status\":1,\"product\":{\"product_name\":\"Nutella\",\"brands\":\"Ferrero\","
                + "\"nutriments\":{\"energy-kcal_100g\":539,\"proteins_100g\":6.3,"
                + "\"carbohydrates_100g\":57.5,\"fat_100g\":30.9,\"sugars_100g\":56.3}}}");

        OpenFoodFactsClient client = new OpenFoodFactsClient(baseUrl);
        Optional<CatalogFood> result = client.lookupByBarcode("3017620422003");

        assertThat(result).isPresent();
        CatalogFood food = result.get();
        assertThat(food.foodId()).isEqualTo("off-3017620422003");
        assertThat(food.source()).isEqualTo(FoodSource.OPEN_FOOD_FACTS);
        assertThat(food.sourceRef()).isEqualTo("3017620422003");
        assertThat(food.status()).isEqualTo(FoodStatus.UNVERIFIED);
        assertThat(food.macrosPer100g().caloriesKcal()).isEqualTo(539.0);
    }

    @Test
    void returnsEmptyOnNotFoundStatus() {
        stub("/api/v2/product/0000000000000.json", 200, "{\"status\":0}");
        OpenFoodFactsClient client = new OpenFoodFactsClient(baseUrl);
        assertThat(client.lookupByBarcode("0000000000000")).isEmpty();
    }

    @Test
    void returnsEmptyOnHttpError() {
        // No context registered for this path -> the stub server returns 404.
        OpenFoodFactsClient client = new OpenFoodFactsClient(baseUrl);
        assertThat(client.lookupByBarcode("9999999999999")).isEmpty();
    }
}
