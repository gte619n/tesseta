package com.gte619n.healthfitness.integrations.withings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WithingsApiClientTest {

    private HttpServer server;
    private WithingsApiClient client;
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new WithingsApiClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Test
    void sleepSummaryYieldsSleepAndRestingHrPoints() {
        responseBody = """
            {"status":0,"body":{"series":[
              {"date":"2026-09-01","data":{"total_sleep_time":27000,"sleep_score":82,
                "hr_min":52,"hr_average":58,"hr_max":71}}
            ]}}
            """;

        List<DailyMetricDataPoint> points = client.listSleepMetrics(
            "at", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"));

        assertThat(points).extracting(
                DailyMetricDataPoint::type, DailyMetricDataPoint::date,
                DailyMetricDataPoint::value, DailyMetricDataPoint::sleepScore)
            .containsExactlyInAnyOrder(
                tuple(DailyMetricDataType.SLEEP, LocalDate.of(2026, 9, 1), 450, 82),
                tuple(DailyMetricDataType.RESTING_HEART_RATE, LocalDate.of(2026, 9, 1), 52, null));
        assertThat(points).allMatch(p -> p.sourcePlatform().equals("WITHINGS"));
    }

    @Test
    void measuresScaleByUnitExponent() {
        responseBody = """
            {"status":0,"body":{"measuregrps":[
              {"grpid":111,"date":1756684800,"attrib":0,"measures":[
                {"value":80710,"type":1,"unit":-3},
                {"value":183,"type":6,"unit":-1}
              ]}
            ]}}
            """;

        List<WithingsBodyPoint> points = client.listBodyMeasurements(
            "at", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(points).extracting(
                WithingsBodyPoint::metric, WithingsBodyPoint::value, WithingsBodyPoint::recordId)
            .containsExactly(
                tuple(BodyCompositionMetric.WEIGHT_KG, 80.71, "111"),
                tuple(BodyCompositionMetric.BODY_FAT_PERCENT, 18.3, "111"));
    }

    @Test
    void nonZeroStatusThrows() {
        responseBody = """
            {"status":503,"body":{},"error":"Invalid params"}
            """;

        assertThatThrownBy(() -> client.listSleepMetrics(
            "at", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z")))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void expiredAccessTokenSurfacesAsAuthException() {
        responseBody = """
            {"status":401,"body":{},"error":"Invalid token"}
            """;

        assertThatThrownBy(() -> client.listBodyMeasurements(
            "at", Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z")))
            .isInstanceOf(WithingsAuthException.class);
    }
}
