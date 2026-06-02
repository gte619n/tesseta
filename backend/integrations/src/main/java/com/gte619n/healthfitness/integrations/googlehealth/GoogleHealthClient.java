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
import java.time.ZoneOffset;
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
            physicalTimeFilter(dataType.filterFieldName(), from, to),
            dp -> BodyCompositionMapper.fromJson(dp, dataType),
            GoogleHealthDataPoint::recordId);
    }

    // Day-grained activity / vitals metrics (steps, resting HR, HRV, sleep).
    // Same paged HTTP shape as body composition, but daily roll-up types are
    // filtered on their civil `date` member (YYYY-MM-DD) — they have no
    // sample_time.physical_time, and filtering on it is rejected with 400.
    public List<DailyMetricDataPoint> listDailyMetricPoints(
        String accessToken,
        DailyMetricDataType dataType,
        Instant from,
        Instant to
    ) {
        return listPaged(
            accessToken,
            dataType.urlSegment(),
            dailyDateFilter(dataType.filterFieldName(), from, to),
            dp -> DailyMetricMapper.fromJson(dp, dataType),
            DailyMetricDataPoint::recordId);
    }

    // Sample/interval types (body composition): filter on the physical
    // timestamp. `>=` and `<` only; `to` is exclusive.
    private static String physicalTimeFilter(String field, Instant from, Instant to) {
        return String.format(
            "%s.sample_time.physical_time >= \"%s\" AND %s.sample_time.physical_time < \"%s\"",
            field, from, field, to);
    }

    // Daily roll-up types: filter on the civil `date` member with ISO
    // YYYY-MM-DD literals (verified against the live API). The upper bound is
    // the day AFTER `to`'s UTC date so the current day is included, mirroring
    // the inclusive intent of the callers' [from, now] windows.
    private static String dailyDateFilter(String field, Instant from, Instant to) {
        java.time.LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate toDateExclusive = to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        return String.format(
            "%s.date >= \"%s\" AND %s.date < \"%s\"",
            field, fromDate, field, toDateExclusive);
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
        String filterExpr,
        Function<JsonNode, T> mapper,
        Function<T, String> idFn
    ) {
        List<T> all = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        String pageToken = null;
        String prevPageToken = null;
        int pageCount = 0;
        do {
            JsonNode body = fetchOnePage(accessToken, urlSegment, filterExpr, pageToken);
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
        String filterExpr,
        String pageToken
    ) {
        String url = buildDataPointsUrl(urlSegment, filterExpr, pageToken);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
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

    private String buildDataPointsUrl(
        String urlSegment,
        String filterExpr,
        String pageToken
    ) {
        // The API filter only supports `>=` and `<` (no `<=`, `=`, BETWEEN).
        // The filter expression is built per data-type family by the caller
        // (physical_time for sample/interval types, civil `date` for daily
        // roll-ups).
        StringBuilder url = new StringBuilder(apiBaseUrl)
            .append("/users/me/dataTypes/")
            .append(urlSegment)
            .append("/dataPoints")
            .append("?filter=")
            .append(URLEncoder.encode(filterExpr, StandardCharsets.UTF_8))
            .append("&page_size=1000");
        if (pageToken != null) {
            url.append("&page_token=").append(URLEncoder.encode(pageToken, StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    // Raw, non-throwing single-page fetch used by the admin sync/inspect
    // endpoint to examine the actual API response (data OR error body)
    // without the mapping layer in the way. Returns exactly what the API
    // returned so the real on-the-wire field shapes can be verified.
    public RawResponse rawFirstPage(
        String accessToken,
        String urlSegment,
        String filterFieldName,
        Instant from,
        Instant to
    ) {
        String url = buildDataPointsUrl(urlSegment, physicalTimeFilter(filterFieldName, from, to), null);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new RawResponse(url, response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new RawResponse(url, -1, "request failed: " + e.getMessage());
        }
    }

    // Same as rawFirstPage but WITHOUT a filter — used to discover the real
    // data-point shape (and thus the correct filterable members) when the
    // standard sample_time.physical_time filter is rejected for a data type.
    public RawResponse rawFirstPageNoFilter(String accessToken, String urlSegment, int pageSize) {
        String url = apiBaseUrl + "/users/me/dataTypes/" + urlSegment
            + "/dataPoints?page_size=" + pageSize;
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new RawResponse(url, response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new RawResponse(url, -1, "request failed: " + e.getMessage());
        }
    }

    // Raw single page with a caller-supplied filter expression verbatim —
    // used to discover the correct filterable members for daily types.
    public RawResponse rawFirstPageCustomFilter(
        String accessToken, String urlSegment, String rawFilter, int pageSize) {
        String url = apiBaseUrl + "/users/me/dataTypes/" + urlSegment
            + "/dataPoints?page_size=" + pageSize;
        if (rawFilter != null && !rawFilter.isBlank()) {
            url += "&filter=" + URLEncoder.encode(rawFilter, StandardCharsets.UTF_8);
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET()
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new RawResponse(url, response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new RawResponse(url, -1, "request failed: " + e.getMessage());
        }
    }

    public record RawResponse(String url, int statusCode, String body) {}
}
