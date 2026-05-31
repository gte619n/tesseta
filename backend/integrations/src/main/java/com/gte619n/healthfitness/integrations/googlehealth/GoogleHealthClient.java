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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// REST wrapper over https://health.googleapis.com/v4/users/me/dataTypes/...
// Synchronous-on-virtual-threads; matches the rest of the backend.
//
// Pagination: the API returns a `nextPageToken`; we loop until it's empty.
// The page loop and stall-detection are shared by every data-type family
// (body composition, daily activity metrics) via the generic listPaged().
@Component
public class GoogleHealthClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthClient.class);
    private static final int MAX_PAGES = 200;

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
        return listPaged(
            accessToken,
            dataType.urlSegment(),
            dataType.filterFieldName(),
            from,
            to,
            dp -> BodyCompositionMapper.fromJson(dp, dataType),
            GoogleHealthDataPoint::recordId);
    }

    // Day-grained activity / vitals metrics (steps, resting HR, HRV, sleep).
    // Same HTTP shape as body composition; only the mapper differs.
    public List<DailyMetricDataPoint> listDailyMetricPoints(
        String accessToken,
        DailyMetricDataType dataType,
        Instant from,
        Instant to
    ) {
        return listPaged(
            accessToken,
            dataType.urlSegment(),
            dataType.filterFieldName(),
            from,
            to,
            dp -> DailyMetricMapper.fromJson(dp, dataType),
            DailyMetricDataPoint::recordId);
    }

    // Generic paginated fetch over one data type. Dedupes by recordId across
    // pages — observed in practice that certain data types (notably `weight`)
    // can return the same set of records on every page with a non-empty
    // nextPageToken, turning the loop into a duplicate factory. The seenIds
    // set makes the loop output stable. We also cap MAX_PAGES so a
    // misbehaving API can't drive the backfill to infinity.
    private <T> List<T> listPaged(
        String accessToken,
        String urlSegment,
        String filterFieldName,
        Instant from,
        Instant to,
        Function<JsonNode, T> mapper,
        Function<T, String> idFn
    ) {
        List<T> all = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        String pageToken = null;
        String prevPageToken = null;
        int pageCount = 0;
        do {
            JsonNode body = fetchOnePage(accessToken, urlSegment, filterFieldName, from, to, pageToken);
            JsonNode dataPoints = body.path("dataPoints");
            int newOnThisPage = 0;
            if (dataPoints.isArray()) {
                for (JsonNode dp : dataPoints) {
                    T point = mapper.apply(dp);
                    if (seenIds.add(idFn.apply(point))) {
                        all.add(point);
                        newOnThisPage++;
                    }
                }
            }
            String next = body.path("nextPageToken").asText("");
            String nextNorm = next.isEmpty() ? null : next;
            // If Google hands us a non-empty page token but it's identical
            // to the previous one, OR the page produced zero new records,
            // pagination has stalled — bail out.
            if (nextNorm != null && nextNorm.equals(prevPageToken)) {
                log.warn("Pagination token did not advance for type={} (stopping)", urlSegment);
                break;
            }
            if (nextNorm != null && newOnThisPage == 0) {
                log.warn("Page returned no new recordIds for type={} (stopping)", urlSegment);
                break;
            }
            prevPageToken = pageToken;
            pageToken = nextNorm;
            pageCount++;
            if (pageCount >= MAX_PAGES) {
                log.warn("Hit MAX_PAGES={} for type={} (stopping)", MAX_PAGES, urlSegment);
                break;
            }
        } while (pageToken != null);
        return all;
    }

    private JsonNode fetchOnePage(
        String accessToken,
        String urlSegment,
        String filterFieldName,
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
            filterFieldName, from,
            filterFieldName, to);
        StringBuilder url = new StringBuilder(apiBaseUrl)
            .append("/users/me/dataTypes/")
            .append(urlSegment)
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
                    "Google Health API " + urlSegment + " list failed ("
                        + response.statusCode() + "): " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Google Health API call failed", e);
        }
    }
}
