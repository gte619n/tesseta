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

// Admin client for projects.subscribers. Used by the one-time subscriber
// registration script (and corresponding integration test). Not on the
// request path.
//
// All calls require a developer-issued access token (passed in by the
// caller), not a per-user token — registering a webhook subscriber is a
// project-level operation.
@Component
public class GoogleHealthSubscriberClient {

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
        List<GoogleHealthDataType> dataTypes
    ) {
        ObjectNode body = mapper.createObjectNode();
        body.put("endpointUri", endpointUri);
        ObjectNode authorization = body.putObject("authorization");
        authorization.put("secret", webhookSecret);
        ArrayNode types = body.putArray("dataTypes");
        for (GoogleHealthDataType t : dataTypes) {
            types.add(t.urlSegment());
        }
        // Try create. If it already exists, fall back to patch.
        int createStatus = post(developerAccessToken,
            apiBaseUrl + "/projects/" + projectId + "/subscribers?subscriberId=" + subscriberId,
            body);
        if (createStatus == 409 || createStatus == 400) {
            // Already exists or update needed. PATCH with full body.
            patch(developerAccessToken,
                apiBaseUrl + "/projects/" + projectId + "/subscribers/" + subscriberId
                    + "?updateMask=endpointUri,authorization,dataTypes",
                body);
        } else if (createStatus / 100 != 2) {
            throw new RuntimeException("Subscriber create failed: " + createStatus);
        }
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
