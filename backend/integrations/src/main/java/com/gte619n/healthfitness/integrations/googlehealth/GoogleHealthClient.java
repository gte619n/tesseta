package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
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

    // Day-grained activity / vitals metrics. Three of the four model cleanly
    // as one value per civil day, but they reach it different ways:
    //   - RESTING_HEART_RATE, HRV: genuine daily roll-up data types. Listed
    //     and filtered on their civil `date` member.
    //   - STEPS: an interval (per-minute) type with no daily form. We POST
    //     :dailyRollUp to get one countSum per civil day.
    //   - SLEEP: a session type; :dailyRollUp is unsupported and a sleep score
    //     isn't exposed, so it is not handled here yet (see DailyMetricDataType
    //     notes) — callers get an empty list rather than a 400.
    public List<DailyMetricDataPoint> listDailyMetricPoints(
        String accessToken,
        DailyMetricDataType dataType,
        Instant from,
        Instant to
    ) {
        return switch (dataType) {
            case STEPS -> listDailyStepTotals(accessToken, from, to);
            case SLEEP -> listDailySleepMinutes(accessToken, from, to);
            case RESTING_HEART_RATE, HRV -> listPaged(
                accessToken,
                dataType.urlSegment(),
                dailyDateFilter(dataType.filterFieldName(), from, to),
                dp -> DailyMetricMapper.fromJson(dp, dataType),
                DailyMetricDataPoint::recordId);
        };
    }

    // Steps as one total per civil day via the :dailyRollUp aggregation.
    // Response shape (verified live):
    //   { "rollupDataPoints": [ { "civilStartTime": { "date": {y,m,d} },
    //                             "steps": { "countSum": "6975" } } ] }
    private static final int ROLLUP_MAX_RANGE_DAYS = 90;

    private List<DailyMetricDataPoint> listDailyStepTotals(
        String accessToken, Instant from, Instant to) {
        // dailyRollUp caps the range at 90 days; the backfill hands us much
        // wider windows, so sub-chunk into <=90-day spans and concatenate.
        java.time.LocalDate windowStart = from.atZone(ZoneOffset.UTC).toLocalDate();
        java.time.LocalDate windowEnd = to.atZone(ZoneOffset.UTC).toLocalDate();
        List<DailyMetricDataPoint> out = new ArrayList<>();
        java.time.LocalDate cursor = windowStart;
        while (cursor.isBefore(windowEnd)) {
            java.time.LocalDate chunkEnd = cursor.plusDays(ROLLUP_MAX_RANGE_DAYS);
            if (chunkEnd.isAfter(windowEnd)) chunkEnd = windowEnd;
            out.addAll(fetchStepTotalsChunk(accessToken, cursor, chunkEnd));
            cursor = chunkEnd;
        }
        return out;
    }

    private List<DailyMetricDataPoint> fetchStepTotalsChunk(
        String accessToken, java.time.LocalDate fromDate, java.time.LocalDate toDate) {
        RawResponse resp = postDailyRollUp(accessToken, "steps", fromDate, toDate);
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException(
                "Google Health steps dailyRollUp failed (" + resp.statusCode() + "): " + resp.body());
        }
        List<DailyMetricDataPoint> out = new ArrayList<>();
        try {
            JsonNode body = mapper.readTree(resp.body());
            for (JsonNode p : body.path("rollupDataPoints")) {
                JsonNode d = p.path("civilStartTime").path("date");
                if (!d.hasNonNull("year")) continue;
                java.time.LocalDate day =
                    java.time.LocalDate.of(d.path("year").asInt(), d.path("month").asInt(),
                        d.path("day").asInt());
                int count = p.path("steps").path("countSum").asInt();
                out.add(new DailyMetricDataPoint(
                    null, null, "steps:" + day, DailyMetricDataType.STEPS, day, count,
                    null, "UNKNOWN", "UNKNOWN"));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to parse steps dailyRollUp response", e);
        }
        return out;
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

    // Sleep as total daily asleep-minutes. Sleep is a session type with no
    // daily roll-up, so we list sessions in the window, sum each session's
    // asleep-stage durations, and accumulate per local wake date (multiple
    // sessions on one day are summed). No sleep score is exposed by this data
    // type, so sleepScore stays null. Verified shape (live):
    //   { "sleep": { "interval": {startTime,endTime,endUtcOffset},
    //                "stages": [ { "type": "LIGHT", startTime, endTime }, ... ] } }
    private List<DailyMetricDataPoint> listDailySleepMinutes(
        String accessToken, Instant from, Instant to) {
        // Sleep is a session type with no daily form and no rollup, but it IS
        // server-side filterable on the session's civil END (wake) time with a
        // YYYY-MM-DD literal — the same date-windowed style as the daily-*
        // types. We attribute each session to its wake date and sum the asleep
        // stage durations per day (multiple sessions on a day are summed).
        LocalDate fromDay = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDayExclusive = to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        String filter = String.format(
            "sleep.interval.civil_end_time >= \"%s\" AND sleep.interval.civil_end_time < \"%s\"",
            fromDay, toDayExclusive);
        Map<LocalDate, Integer> minutesByDay = new HashMap<>();
        String pageToken = null;
        String prevToken = null;
        int pages = 0;
        do {
            JsonNode body = fetchOnePage(accessToken, "sleep", filter, pageToken);
            for (JsonNode dp : body.path("dataPoints")) {
                JsonNode sleep = dp.path("sleep");
                LocalDate day = sleepWakeDate(sleep);
                int minutes = asleepMinutes(sleep);
                if (day != null && minutes > 0) {
                    minutesByDay.merge(day, minutes, Integer::sum);
                }
            }
            String next = body.path("nextPageToken").asText("");
            String norm = next.isEmpty() ? null : next;
            if (norm != null && norm.equals(prevToken)) break;
            prevToken = pageToken;
            pageToken = norm;
            pages++;
        } while (pageToken != null && pages < MAX_PAGES);

        List<DailyMetricDataPoint> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : minutesByDay.entrySet()) {
            out.add(new DailyMetricDataPoint(
                null, null, "sleep:" + e.getKey(), DailyMetricDataType.SLEEP,
                e.getKey(), e.getValue(), null, "UNKNOWN", "UNKNOWN"));
        }
        return out;
    }

    // Sum durations of asleep stages (LIGHT/DEEP/REM/ASLEEP), excluding
    // AWAKE/RESTLESS/OUT_OF_BED. Falls back to the whole interval span when a
    // session carries no stage breakdown.
    private static int asleepMinutes(JsonNode sleep) {
        JsonNode stages = sleep.path("stages");
        if (stages.isArray() && !stages.isEmpty()) {
            long secs = 0;
            for (JsonNode s : stages) {
                String type = s.path("type").asText("");
                if (type.equals("LIGHT") || type.equals("DEEP")
                    || type.equals("REM") || type.equals("ASLEEP")) {
                    secs += secondsBetween(
                        s.path("startTime").asText(""), s.path("endTime").asText(""));
                }
            }
            return (int) (secs / 60);
        }
        JsonNode interval = sleep.path("interval");
        return (int) (secondsBetween(
            interval.path("startTime").asText(""), interval.path("endTime").asText("")) / 60);
    }

    private static long secondsBetween(String startIso, String endIso) {
        if (startIso.isEmpty() || endIso.isEmpty()) return 0;
        try {
            return Duration.between(Instant.parse(startIso), Instant.parse(endIso)).getSeconds();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // Attribute a session to the local wake date (interval end + reported UTC
    // offset). Sleep UX conventionally shows last night's sleep on the wake day.
    private static LocalDate sleepWakeDate(JsonNode sleep) {
        JsonNode interval = sleep.path("interval");
        String end = interval.path("endTime").asText("");
        if (end.isEmpty()) return null;
        try {
            return Instant.parse(end)
                .atOffset(parseOffset(interval.path("endUtcOffset").asText("")))
                .toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // "-14400s" -> ZoneOffset.ofTotalSeconds(-14400); empty/invalid -> UTC.
    private static ZoneOffset parseOffset(String s) {
        if (s == null || s.isEmpty()) return ZoneOffset.UTC;
        try {
            String n = s.endsWith("s") ? s.substring(0, s.length() - 1) : s;
            return ZoneOffset.ofTotalSeconds(Integer.parseInt(n.trim()));
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
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
            .append("?page_size=1000");
        // Some data types (sleep) expose no filterable time member — list
        // unfiltered and window client-side.
        if (filterExpr != null && !filterExpr.isBlank()) {
            url.append("&filter=").append(URLEncoder.encode(filterExpr, StandardCharsets.UTF_8));
        }
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

    // Public inspect wrapper over the dailyRollUp POST (used by the admin
    // endpoint to examine the raw aggregation response).
    public RawResponse rawDailyRollUp(
        String accessToken, String urlSegment, java.time.LocalDate from, java.time.LocalDate to) {
        return postDailyRollUp(accessToken, urlSegment, from, to);
    }

    // dailyRollUp POST — aggregates an interval data type into one value per
    // civil day. `range` is a CivilTimeInterval of CivilDateTimes; we send
    // date-only civil times. Non-throwing: returns the raw status/body.
    private RawResponse postDailyRollUp(
        String accessToken, String urlSegment, java.time.LocalDate from, java.time.LocalDate to) {
        String url = apiBaseUrl + "/users/me/dataTypes/" + urlSegment + "/dataPoints:dailyRollUp";
        String body = String.format(
            "{\"range\":{\"start\":{\"date\":{\"year\":%d,\"month\":%d,\"day\":%d}},"
                + "\"end\":{\"date\":{\"year\":%d,\"month\":%d,\"day\":%d}}},\"windowSizeDays\":1}",
            from.getYear(), from.getMonthValue(), from.getDayOfMonth(),
            to.getYear(), to.getMonthValue(), to.getDayOfMonth());
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new RawResponse(url + " body=" + body, response.statusCode(), response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new RawResponse(url, -1, "request failed: " + e.getMessage());
        }
    }

    public record RawResponse(String url, int statusCode, String body) {}
}
