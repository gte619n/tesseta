package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Admin client for projects.subscribers — the Java mirror of
// infra/scripts/setup-google-health-subscriber.sh, for registering the
// webhook subscriber from code rather than a shell one-off. Not on the
// request path.
//
// All calls require a developer-issued access token (passed in by the
// caller), not a per-user token — registering a webhook subscriber is a
// project-level operation.
//
// Request schema matches the live API (confirmed against the setup script):
//   { endpointUri, endpointAuthorization: { secret },
//     subscriberConfigs: [ { dataTypes: [...], subscriptionCreatePolicy } ] }
// dataTypes are the kebab url-segments (weight, body-fat, steps, sleep,
// daily-resting-heart-rate, daily-heart-rate-variability) — i.e.
// GoogleHealthDataType.urlSegment() / DailyMetricDataType.urlSegment().
@Component
public class GoogleHealthSubscriberClient {

    private static final String CREATE_POLICY = "AUTOMATIC";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBaseUrl;

    public GoogleHealthSubscriberClient(
        @Value("${app.googlehealth.api-base-url:https://health.googleapis.com/v4}") String apiBaseUrl
    ) {
        this.http = HttpClient.newBuilder().build();
        this.apiBaseUrl = apiBaseUrl;
    }

    public void createOrUpdate(
        String developerAccessToken,
        String projectId,
        String subscriberId,
        String endpointUri,
        String webhookSecret,
        List<String> dataTypeSegments
    ) {
        ObjectNode body = requestBody(endpointUri, webhookSecret, dataTypeSegments);
        // Try create. If it already exists, fall back to patch.
        int createStatus = post(developerAccessToken,
            apiBaseUrl + "/projects/" + projectId + "/subscribers?subscriberId=" + subscriberId,
            body);
        if (createStatus == 409 || createStatus == 400) {
            // Already exists or update needed. PATCH with full body.
            patch(developerAccessToken,
                apiBaseUrl + "/projects/" + projectId + "/subscribers/" + subscriberId
                    + "?updateMask=endpointUri,endpointAuthorization,subscriberConfigs",
                body);
        } else if (createStatus / 100 != 2) {
            throw new RuntimeException("Subscriber create failed: " + createStatus);
        }
    }

    // Builds the subscriber request body in the live API's shape. Package
    // private so the schema can be asserted without an HTTP round-trip.
    ObjectNode requestBody(String endpointUri, String webhookSecret, List<String> dataTypeSegments) {
        ObjectNode body = mapper.createObjectNode();
        body.put("endpointUri", endpointUri);
        body.putObject("endpointAuthorization").put("secret", webhookSecret);
        ObjectNode config = mapper.createObjectNode();
        ArrayNode types = config.putArray("dataTypes");
        for (String segment : dataTypeSegments) {
            types.add(segment);
        }
        config.put("subscriptionCreatePolicy", CREATE_POLICY);
        body.putArray("subscriberConfigs").add(config);
        return body;
    }

    private int post(String token, String url, ObjectNode body) {
        return send(token, "POST", url, body);
    }

    private int patch(String token, String url, ObjectNode body) {
        int status = send(token, "PATCH", url, body);
        if (status / 100 != 2) {
            throw new RuntimeException("Subscriber patch failed: " + status);
        }
        return status;
    }

    private int send(String token, String method, String url, ObjectNode body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return response.statusCode();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Subscriber API call failed", e);
        }
    }
}
