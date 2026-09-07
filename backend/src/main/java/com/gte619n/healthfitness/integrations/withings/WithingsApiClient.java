package com.gte619n.healthfitness.integrations.withings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// REST wrapper over the Withings Health API (https://wbsapi.withings.net).
// Synchronous-on-virtual-threads, matching the rest of the backend and the
// Google Health client.
//
// Like the OAuth client, every response is a { "status": 0, "body": {...} }
// envelope with HTTP 200 even on logical errors; readBody() unwraps it and
// throws on a non-zero status.
//
// Two data families are pulled:
//   - Sleep (Sleep v2 getsummary): total sleep minutes + sleep score, plus the
//     night's minimum heart rate as a resting-HR proxy. Emitted as
//     provider-neutral DailyMetricDataPoint(s) so the existing DailyMetric
//     mapping/merge is reused verbatim.
//   - Body (Measure getmeas): weight + fat ratio, emitted as WithingsBodyPoint.
@Component
public class WithingsApiClient {

    /** The DeviceSync / BodyCompositionMeasurement source-platform identifier. */
    public static final String PLATFORM = "WITHINGS";

    private static final Logger log = LoggerFactory.getLogger(WithingsApiClient.class);
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // Sleep summary data fields we request (comma-joined into data_fields).
    private static final String SLEEP_FIELDS = "total_sleep_time,sleep_score,hr_average,hr_min,hr_max";

    // Withings measure type ids (getmeas `type`): 1 = weight (kg), 6 = fat ratio (%).
    private static final int MEAS_WEIGHT = 1;
    private static final int MEAS_FAT_RATIO = 6;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBaseUrl;

    public WithingsApiClient(
        @Value("${app.withings.api-base-url:https://wbsapi.withings.net}") String apiBaseUrl
    ) {
        this.http = HttpClient.newBuilder().build();
        this.apiBaseUrl = apiBaseUrl;
    }

    // ---- Sleep -------------------------------------------------------------

    /**
     * Sleep summaries for [from, to] as day-grained DailyMetricDataPoints. Each
     * night yields a SLEEP point (total minutes + score) and, when present, a
     * RESTING_HEART_RATE point (the night's minimum HR — the closest resting
     * proxy the Sleep Analyzer exposes). HRV is not in the basic summary, so no
     * HRV point is produced.
     */
    public List<DailyMetricDataPoint> listSleepMetrics(String accessToken, Instant from, Instant to) {
        String body = formEncode(
            "action", "getsummary",
            "startdateymd", YMD.format(from.atZone(ZoneOffset.UTC).toLocalDate()),
            "enddateymd", YMD.format(to.atZone(ZoneOffset.UTC).toLocalDate()),
            "data_fields", SLEEP_FIELDS
        );
        JsonNode responseBody = post("/v2/sleep", accessToken, body);
        List<DailyMetricDataPoint> out = new ArrayList<>();
        for (JsonNode series : responseBody.path("series")) {
            LocalDate day = parseDay(series);
            if (day == null) continue;
            JsonNode data = series.path("data");
            int totalSeconds = data.path("total_sleep_time").asInt(0);
            if (totalSeconds > 0) {
                Integer score = data.hasNonNull("sleep_score") ? data.path("sleep_score").asInt() : null;
                out.add(new DailyMetricDataPoint(
                    null, null, "withings:sleep:" + day, DailyMetricDataType.SLEEP,
                    day, totalSeconds / 60, score, PLATFORM, "AUTOMATIC"));
            }
            if (data.hasNonNull("hr_min") && data.path("hr_min").asInt() > 0) {
                out.add(new DailyMetricDataPoint(
                    null, null, "withings:resting-hr:" + day, DailyMetricDataType.RESTING_HEART_RATE,
                    day, data.path("hr_min").asInt(), null, PLATFORM, "AUTOMATIC"));
            }
        }
        return out;
    }

    // getsummary series carry the night's date as a "date":"YYYY-MM-DD" field.
    private static LocalDate parseDay(JsonNode series) {
        String date = series.path("date").asText("");
        if (date.isEmpty()) return null;
        try {
            return LocalDate.parse(date);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- Body composition --------------------------------------------------

    /**
     * Weight + fat-ratio measurements for [from, to]. `from`/`to` are converted
     * to the epoch-second startdate/enddate getmeas expects.
     */
    public List<WithingsBodyPoint> listBodyMeasurements(String accessToken, Instant from, Instant to) {
        String body = formEncode(
            "action", "getmeas",
            "meastypes", MEAS_WEIGHT + "," + MEAS_FAT_RATIO,
            "category", "1", // real measures (not user objectives)
            "startdate", Long.toString(from.getEpochSecond()),
            "enddate", Long.toString(to.getEpochSecond())
        );
        JsonNode responseBody = post("/measure", accessToken, body);
        List<WithingsBodyPoint> out = new ArrayList<>();
        for (JsonNode grp : responseBody.path("measuregrps")) {
            String grpId = grp.path("grpid").asText("");
            Instant sampleTime = Instant.ofEpochSecond(grp.path("date").asLong(0));
            String method = isManual(grp.path("attrib").asInt(0)) ? "MANUAL" : "AUTOMATIC";
            for (JsonNode m : grp.path("measures")) {
                int type = m.path("type").asInt(-1);
                BodyCompositionMetric metric = switch (type) {
                    case MEAS_WEIGHT -> BodyCompositionMetric.WEIGHT_KG;
                    case MEAS_FAT_RATIO -> BodyCompositionMetric.BODY_FAT_PERCENT;
                    default -> null;
                };
                if (metric == null) continue;
                // Real reading = value * 10^unit (unit is a negative exponent).
                // Scale with BigDecimal so clean decimals stay clean (80710e-3
                // -> 80.71, not 80.71000000000001) before we store/display them.
                double value = java.math.BigDecimal.valueOf(m.path("value").asLong())
                    .scaleByPowerOfTen(m.path("unit").asInt(0))
                    .doubleValue();
                out.add(new WithingsBodyPoint(grpId, metric, value, sampleTime, method));
            }
        }
        return out;
    }

    // Withings measuregrp attrib: 0/1 = device auto-captured, 2/4 = manual entry.
    private static boolean isManual(int attrib) {
        return attrib == 2 || attrib == 4;
    }

    // ---- transport ---------------------------------------------------------

    private JsonNode post(String path, String accessToken, String formBody) {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + path))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "Withings API " + path + " failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            int status = json.path("status").asInt(-1);
            if (status != 0) {
                // 401 here means the access token lapsed — surface as an auth
                // error so callers can refresh + retry. Everything else is a
                // generic API failure.
                String detail = "Withings API " + path + " status=" + status + ": " + response.body();
                if (status == 401) {
                    throw new WithingsAuthException(detail);
                }
                throw new RuntimeException(detail);
            }
            return json.path("body");
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Withings API call failed", e);
        }
    }

    private static String formEncode(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
