package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// REST wrapper over https://health.googleapis.com/v4/users/me/dataTypes/...
// Synchronous-on-virtual-threads; matches the rest of the backend.
//
// Pagination: the API returns a `nextPageToken`; we loop until it's empty.
@Component
public class GoogleHealthClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBaseUrl;

    public GoogleHealthClient(
        @Value("${app.googlehealth.api-base-url:https://health.googleapis.com/v4}") String apiBaseUrl
    ) {
        this.http = HttpClient.newBuilder().build();
        this.apiBaseUrl = apiBaseUrl;
    }

    public List<GoogleHealthDataPoint> listDataPoints(
        String accessToken,
        GoogleHealthDataType dataType,
        Instant from,
        Instant to
    ) {
        List<GoogleHealthDataPoint> all = new ArrayList<>();
        String pageToken = null;
        do {
            JsonNode body = fetchOnePage(accessToken, dataType, from, to, pageToken);
            JsonNode dataPoints = body.path("dataPoints");
            if (dataPoints.isArray()) {
                for (JsonNode dp : dataPoints) {
                    all.add(BodyCompositionMapper.fromJson(dp, dataType));
                }
            }
            String next = body.path("nextPageToken").asText("");
            pageToken = next.isEmpty() ? null : next;
        } while (pageToken != null);
        return all;
    }

    private JsonNode fetchOnePage(
        String accessToken,
        GoogleHealthDataType dataType,
        Instant from,
        Instant to,
        String pageToken
    ) {
        // Google Health filter only supports `>=` and `<` on physical_time
        // — neither `<=`, `=`, nor BETWEEN are accepted. We treat `to` as
        // exclusive in this client; callers wanting an inclusive upper
        // bound should pass `to.plusSeconds(1)` or similar.
        String filter = String.format(
            "%s.sample_time.physical_time >= \"%s\" AND %s.sample_time.physical_time < \"%s\"",
            dataType.filterFieldName(), from,
            dataType.filterFieldName(), to);
        StringBuilder url = new StringBuilder(apiBaseUrl)
            .append("/users/me/dataTypes/")
            .append(dataType.urlSegment())
            .append("/dataPoints")
            .append("?filter=")
            .append(URLEncoder.encode(filter, StandardCharsets.UTF_8))
            .append("&page_size=1000");
        if (pageToken != null) {
            url.append("&page_token=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url.toString()))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "Google Health API " + dataType.urlSegment() + " list failed ("
                        + response.statusCode() + "): " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Google Health API call failed", e);
        }
    }
}
