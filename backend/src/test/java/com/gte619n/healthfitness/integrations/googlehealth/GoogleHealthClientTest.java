package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
                "weight": { "weightGrams": 80000,
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
                "weight": { "weightGrams": 80000,
                  "sampleTime": { "physicalTime": "2026-05-01T00:00:00Z" } } }
            ], "nextPageToken": "abc" }
            """);
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u/dataTypes/weight/dataPoints/r2",
                "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
                "weight": { "weightGrams": 81000,
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

    // --- daily metrics: the three distinct fetch strategies, against the
    //     verbatim payload shapes captured live from the API. ---

    @Test
    void dailyMetrics_restingHeartRate_listsWithCivilDateFilter() {
        responseBodies.add("""
            { "dataPoints": [
              { "dataSource": { "platform": "FITBIT", "recordingMethod": "DERIVED" },
                "dailyRestingHeartRate": {
                  "date": { "year": 2026, "month": 6, "day": 1 }, "beatsPerMinute": "66" } }
            ] }
            """);
        List<DailyMetricDataPoint> pts = client.listDailyMetricPoints(
            "tok", DailyMetricDataType.RESTING_HEART_RATE,
            Instant.parse("2026-05-20T00:00:00Z"), Instant.parse("2026-06-02T00:00:00Z"));

        assertThat(pts).singleElement().satisfies(p -> {
            assertThat(p.value()).isEqualTo(66);
            assertThat(p.date()).isEqualTo(LocalDate.of(2026, 6, 1));
        });
        assertThat(requests.get(0).query.get("filter")).contains("daily_resting_heart_rate.date >= \"2026-05-20\"");
    }

    @Test
    void dailyMetrics_steps_aggregatesViaDailyRollUp() {
        responseBodies.add("""
            { "rollupDataPoints": [
              { "civilStartTime": { "date": { "year": 2026, "month": 6, "day": 1 } },
                "steps": { "countSum": "6975" } },
              { "civilStartTime": { "date": { "year": 2026, "month": 5, "day": 31 } },
                "steps": { "countSum": "8813" } } ] }
            """);
        List<DailyMetricDataPoint> pts = client.listDailyMetricPoints(
            "tok", DailyMetricDataType.STEPS,
            Instant.parse("2026-05-30T00:00:00Z"), Instant.parse("2026-06-02T00:00:00Z"));

        assertThat(pts).extracting(DailyMetricDataPoint::date, DailyMetricDataPoint::value)
            .containsExactlyInAnyOrder(
                tuple(LocalDate.of(2026, 6, 1), 6975),
                tuple(LocalDate.of(2026, 5, 31), 8813));
        // Steps uses the dailyRollUp collection-action endpoint, not list.
        assertThat(requests.get(0).path).isEqualTo("/users/me/dataTypes/steps/dataPoints:dailyRollUp");
    }

    @Test
    void dailyMetrics_sleep_sumsAsleepStagesPerWakeDay() {
        responseBodies.add("""
            { "dataPoints": [
              { "name": "users/u/dataTypes/sleep/dataPoints/s1",
                "dataSource": { "platform": "FITBIT", "recordingMethod": "DERIVED" },
                "sleep": { "interval": { "startTime": "2026-06-01T02:24:00Z",
                                          "endTime": "2026-06-01T09:46:00Z", "endUtcOffset": "-14400s" },
                  "stages": [
                    { "startTime": "2026-06-01T02:24:00Z", "endTime": "2026-06-01T02:39:30Z", "type": "AWAKE" },
                    { "startTime": "2026-06-01T02:39:30Z", "endTime": "2026-06-01T03:09:30Z", "type": "LIGHT" },
                    { "startTime": "2026-06-01T03:09:30Z", "endTime": "2026-06-01T04:09:30Z", "type": "REM" } ] } }
            ] }
            """);
        List<DailyMetricDataPoint> pts = client.listDailyMetricPoints(
            "tok", DailyMetricDataType.SLEEP,
            Instant.parse("2026-05-31T00:00:00Z"), Instant.parse("2026-06-02T00:00:00Z"));

        assertThat(pts).singleElement().satisfies(p -> {
            assertThat(p.date()).isEqualTo(LocalDate.of(2026, 6, 1)); // wake day (local)
            assertThat(p.value()).isEqualTo(90);                      // LIGHT 30 + REM 60; AWAKE excluded
            assertThat(p.sleepScore()).isNull();
        });
        assertThat(requests.get(0).query.get("filter")).contains("sleep.interval.civil_end_time");
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
