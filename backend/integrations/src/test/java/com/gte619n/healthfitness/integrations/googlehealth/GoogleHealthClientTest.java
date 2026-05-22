package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoogleHealthClientTest {

    private HttpServer server;
    private GoogleHealthClient client;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final List<String> responseBodies = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new GoogleHealthClient(baseUrl);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listDataPointsBuildsExpectedUrl() {
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u1/dataTypes/weight/dataPoints/r1",
                "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
                "weight": { "kilograms": 80.0,
                  "sampleTime": { "physicalTime": "2026-05-20T07:45:00Z" } } }
            ] }
            """);

        Instant from = Instant.parse("2026-04-20T00:00:00Z");
        Instant to = Instant.parse("2026-05-20T00:00:00Z");
        List<GoogleHealthDataPoint> points = client.listDataPoints(
            "test-token", GoogleHealthDataType.WEIGHT, from, to);

        assertThat(points).hasSize(1);
        assertThat(requests).hasSize(1);
        RecordedRequest req = requests.get(0);
        assertThat(req.path).isEqualTo("/users/me/dataTypes/weight/dataPoints");
        assertThat(req.headers).containsEntry("authorization", "Bearer test-token");
        assertThat(req.query)
            .containsEntry("filter",
                "weight.sample_time.physical_time >= \"2026-04-20T00:00:00Z\" AND "
                    + "weight.sample_time.physical_time < \"2026-05-20T00:00:00Z\"");
        assertThat(req.query).containsKey("page_size");
    }

    @Test
    void listDataPointsFollowsPagination() {
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u/dataTypes/weight/dataPoints/r1",
                "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
                "weight": { "kilograms": 80,
                  "sampleTime": { "physicalTime": "2026-05-01T00:00:00Z" } } }
            ], "nextPageToken": "abc" }
            """);
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u/dataTypes/weight/dataPoints/r2",
                "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
                "weight": { "kilograms": 81,
                  "sampleTime": { "physicalTime": "2026-05-02T00:00:00Z" } } }
            ] }
            """);

        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-31T00:00:00Z");
        List<GoogleHealthDataPoint> points = client.listDataPoints(
            "tok", GoogleHealthDataType.WEIGHT, from, to);

        assertThat(points).hasSize(2);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).query).containsEntry("page_token", "abc");
    }

    @Test
    void mapsAllFourMetricsThroughTheClient() {
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u/dataTypes/body-fat/dataPoints/r",
                "dataSource": { "platform": "WITHINGS", "recordingMethod": "AUTOMATIC" },
                "bodyFat": { "percentage": 19.4,
                  "sampleTime": { "physicalTime": "2026-05-20T07:45:00Z" } } }
            ] }
            """);
        List<GoogleHealthDataPoint> points = client.listDataPoints(
            "tok", GoogleHealthDataType.BODY_FAT,
            Instant.parse("2026-04-01T00:00:00Z"),
            Instant.parse("2026-05-31T00:00:00Z"));
        assertThat(points).singleElement().satisfies(p -> {
            assertThat(p.dataType()).isEqualTo(GoogleHealthDataType.BODY_FAT);
            assertThat(p.value()).isEqualTo(19.4);
            assertThat(p.sourcePlatform()).isEqualTo("WITHINGS");
        });
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        requests.add(new RecordedRequest(exchange.getRequestURI().getPath(), headers, query));
        String body = responseBodies.isEmpty() ? "{}" : responseBodies.remove(0);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new java.util.HashMap<>();
        if (raw == null) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            result.put(
                URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private record RecordedRequest(String path, Map<String, String> headers, Map<String, String> query) {}
}
