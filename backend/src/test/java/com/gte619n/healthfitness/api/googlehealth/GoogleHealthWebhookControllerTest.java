package com.gte619n.healthfitness.api.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import java.net.URI;
import java.time.Instant;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;

// End-to-end test of the webhook endpoint against the real HTTP server.
// Mocks the WebhookHandlerService so we can assert the controller's
// decisions (auth check, probe handling, parse-and-dispatch) without
// needing real Google Health data.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.googlehealth.webhook-secret=Bearer test-webhook-secret"
)
@ActiveProfiles("test")
@Import({TestPersistenceConfig.class, GoogleHealthWebhookControllerTest.HandlerStubConfig.class})
class GoogleHealthWebhookControllerTest {

    @TestConfiguration
    static class HandlerStubConfig {
        @Bean
        WebhookHandlerService webhookHandlerService() {
            return org.mockito.Mockito.mock(WebhookHandlerService.class);
        }

        @Bean
        DailyMetricWebhookHandler dailyMetricWebhookHandler() {
            return org.mockito.Mockito.mock(DailyMetricWebhookHandler.class);
        }
    }

    @LocalServerPort int port;
    @Autowired WebhookHandlerService handler;
    @Autowired DailyMetricWebhookHandler dailyHandler;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetHandlerMock() {
        // Beans are singletons, so previous tests' invocations leak. Reset.
        Mockito.reset(handler, dailyHandler);
    }

    @Test
    void rejectsMissingAuthorizationHeader() throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        verify(handler, never()).handle(any());
    }

    @Test
    void rejectsWrongSecret() throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "wrong-secret")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
        verify(handler, never()).handle(any());
    }

    @Test
    void acceptsAuthorizedProbeWithPartialNotificationShape() throws Exception {
        // Google's domain-verification probe sends a JSON body whose
        // notification fields are blank ('' for healthUserId, startTime,
        // etc.). The endpoint must respond 200, not 500 on a parse error.
        String probeBody = """
            {
              "healthUserId": "",
              "dataType": "",
              "operation": "UPSERT",
              "intervals": [ { "startTime": "", "endTime": "" } ]
            }
            """;
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(probeBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        verify(handler, never()).handle(any());
    }

    @Test
    void acceptsAuthorizedDomainVerificationProbe() throws Exception {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        verify(handler, never()).handle(any());
    }

    @Test
    void parsesAndDispatchesUpsertNotification() throws Exception {
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "weight",
              "operation": "UPSERT",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<WebhookHandlerService.Notification> captor =
            ArgumentCaptor.forClass(WebhookHandlerService.Notification.class);
        verify(handler).handle(captor.capture());
        WebhookHandlerService.Notification got = captor.getValue();
        assertThat(got.healthUserId()).isEqualTo("12345");
        assertThat(got.dataType()).isEqualTo(GoogleHealthDataType.WEIGHT);
        assertThat(got.operation()).isEqualTo(WebhookHandlerService.Operation.UPSERT);
    }

    @Test
    void parsesDeleteNotification() throws Exception {
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "body-fat",
              "operation": "DELETE",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<WebhookHandlerService.Notification> captor =
            ArgumentCaptor.forClass(WebhookHandlerService.Notification.class);
        verify(handler).handle(captor.capture());
        assertThat(captor.getValue().operation()).isEqualTo(WebhookHandlerService.Operation.DELETE);
        assertThat(captor.getValue().dataType()).isEqualTo(GoogleHealthDataType.BODY_FAT);
    }

    @Test
    void routesStepsNotificationToDailyMetricHandler() throws Exception {
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "steps",
              "operation": "UPSERT",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<DailyMetricWebhookHandler.Notification> captor =
            ArgumentCaptor.forClass(DailyMetricWebhookHandler.Notification.class);
        verify(dailyHandler).handle(captor.capture());
        DailyMetricWebhookHandler.Notification got = captor.getValue();
        assertThat(got.healthUserId()).isEqualTo("12345");
        assertThat(got.dataType()).isEqualTo(DailyMetricDataType.STEPS);
        assertThat(got.operation()).isEqualTo(DailyMetricWebhookHandler.Operation.UPSERT);
        // A daily-metric notification must not leak into the body-comp handler.
        verify(handler, never()).handle(any());
    }

    @Test
    void routesSleepNotificationToDailyMetricHandler() throws Exception {
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "sleep",
              "operation": "UPSERT",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<DailyMetricWebhookHandler.Notification> captor =
            ArgumentCaptor.forClass(DailyMetricWebhookHandler.Notification.class);
        verify(dailyHandler).handle(captor.capture());
        assertThat(captor.getValue().dataType()).isEqualTo(DailyMetricDataType.SLEEP);
        verify(handler, never()).handle(any());
    }

    @Test
    void routesDailyRestingHeartRateNotificationToDailyMetricHandler() throws Exception {
        // Google names daily roll-ups with a "daily-" prefix. Routing must
        // match the API identifier exactly; the bare "resting-heart-rate"
        // form would fall through to the unmodeled branch and drop silently.
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "daily-resting-heart-rate",
              "operation": "UPSERT",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<DailyMetricWebhookHandler.Notification> captor =
            ArgumentCaptor.forClass(DailyMetricWebhookHandler.Notification.class);
        verify(dailyHandler).handle(captor.capture());
        assertThat(captor.getValue().dataType()).isEqualTo(DailyMetricDataType.RESTING_HEART_RATE);
        verify(handler, never()).handle(any());
    }

    @Test
    void acknowledgesUnmodeledDataTypeWithoutDispatch() throws Exception {
        String body = """
            {
              "healthUserId": "12345",
              "dataType": "blood-glucose",
              "operation": "UPSERT",
              "intervals": [
                { "startTime": "2026-05-01T00:00:00Z", "endTime": "2026-05-20T00:00:00Z" }
              ]
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        // Unknown type is acked (200) so Google stops retrying; neither
        // handler is invoked.
        assertThat(response.statusCode()).isEqualTo(200);
        verify(handler, never()).handle(any());
        verify(dailyHandler, never()).handle(any());
    }

    @Test
    void parsesRealEnvelopedNotificationFromGoogle() throws Exception {
        // The exact shape the Google Health API posts: every field nested
        // under a top-level "data" envelope, interval times under
        // physicalTimeInterval, and the dataType in camelCase ("bodyFat").
        // See https://developers.google.com/health/webhooks. Before the
        // envelope/physicalTimeInterval fix, this was misclassified as a
        // probe and silently dropped.
        String body = """
            {
              "data": {
                "version": "1",
                "clientProvidedSubscriptionName": "health-fitness-backend",
                "healthUserId": "12345",
                "operation": "UPSERT",
                "dataType": "bodyFat",
                "intervals": [
                  {
                    "physicalTimeInterval": {
                      "startTime": "2026-05-01T00:00:00Z",
                      "endTime": "2026-05-20T00:00:00Z"
                    },
                    "civilIso8601TimeInterval": {
                      "startTime": "2026-04-30T17:00:00",
                      "endTime": "2026-05-19T17:00:00"
                    }
                  }
                ]
              }
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<WebhookHandlerService.Notification> captor =
            ArgumentCaptor.forClass(WebhookHandlerService.Notification.class);
        verify(handler).handle(captor.capture());
        WebhookHandlerService.Notification got = captor.getValue();
        assertThat(got.healthUserId()).isEqualTo("12345");
        assertThat(got.dataType()).isEqualTo(GoogleHealthDataType.BODY_FAT);
        assertThat(got.operation()).isEqualTo(WebhookHandlerService.Operation.UPSERT);
        assertThat(got.intervalStart()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(got.intervalEnd()).isEqualTo(Instant.parse("2026-05-20T00:00:00Z"));
    }

    @Test
    void routesEnvelopedDailyHrvNotificationToDailyMetricHandler() throws Exception {
        // Enveloped daily roll-up with camelCase dataType, as Google sends.
        String body = """
            {
              "data": {
                "healthUserId": "12345",
                "operation": "UPSERT",
                "dataType": "dailyHeartRateVariability",
                "intervals": [
                  {
                    "physicalTimeInterval": {
                      "startTime": "2026-05-01T00:00:00Z",
                      "endTime": "2026-05-02T00:00:00Z"
                    }
                  }
                ]
              }
            }
            """;

        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        ArgumentCaptor<DailyMetricWebhookHandler.Notification> captor =
            ArgumentCaptor.forClass(DailyMetricWebhookHandler.Notification.class);
        verify(dailyHandler).handle(captor.capture());
        assertThat(captor.getValue().dataType()).isEqualTo(DailyMetricDataType.HRV);
        verify(handler, never()).handle(any());
    }

    @Test
    void acceptsVerificationProbeWithoutDispatch() throws Exception {
        // The domain-verification probe posts {"type":"verification"} with no
        // data envelope. It must ack 200 and dispatch nothing.
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/google-health"))
                .header("Authorization", "Bearer test-webhook-secret")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"verification\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        verify(handler, never()).handle(any());
        verify(dailyHandler, never()).handle(any());
    }
}
