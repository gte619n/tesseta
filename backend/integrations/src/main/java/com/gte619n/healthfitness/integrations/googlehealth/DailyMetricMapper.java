package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

// Translates a raw Google Health dataPoint JSON node for a daily-activity
// metric into a DailyMetricDataPoint.
//
// Verified against real API responses for daily-resting-heart-rate and
// daily-heart-rate-variability (see GoogleHealthSyncController). A daily
// roll-up data point looks like:
//   { "dataSource": { "platform": "FITBIT", "recordingMethod": "DERIVED" },
//     "dailyRestingHeartRate": {
//       "date": { "year": 2026, "month": 6, "day": 1 },
//       "beatsPerMinute": "66" } }
// Notably there is NO resource `name`/recordId and the day lives in a
// nested {year,month,day} `date` object — not the sample_time.physical_time
// that body composition uses. The old name-parsing throw-on-missing logic
// would crash on every real daily point.
public final class DailyMetricMapper {

    private DailyMetricMapper() {}

    public static DailyMetricDataPoint fromJson(JsonNode dataPoint, DailyMetricDataType type) {
        JsonNode metricNode = dataPoint.path(type.jsonField());

        int value = extractValue(metricNode, type);
        Integer sleepScore = type == DailyMetricDataType.SLEEP && metricNode.has("score")
            ? metricNode.path("score").asInt()
            : null;

        LocalDate date = parseDate(metricNode, dataPoint);

        JsonNode dataSource = dataPoint.path("dataSource");
        String platform = dataSource.path("platform").asText("UNKNOWN");
        String method = dataSource.path("recordingMethod").asText("UNKNOWN");

        // Daily roll-ups carry no resource name; identity is one point per
        // (type, day). Synthesize a stable recordId from the day so the
        // client's cross-page dedup keeps one reading per day rather than
        // collapsing every empty-id point into one.
        String recordId = type.urlSegment() + ":" + date;
        String name = dataPoint.path("name").asText(null);

        return new DailyMetricDataPoint(
            name,
            null,
            recordId,
            type,
            date,
            value,
            sleepScore,
            platform,
            method
        );
    }

    // Inner value field names per the Google Health API v4 proto messages
    // (Steps.count, DailyRestingHeartRate.beats_per_minute,
    // DailyHeartRateVariability.average_heart_rate_variability_milliseconds,
    // serialized to camelCase in JSON). HRV's average is a double; we round
    // to the nearest whole millisecond since DailyMetric stores an Integer.
    // SLEEP's exact summary field is still being confirmed against a real
    // payload (see the admin sync/inspect endpoint).
    private static int extractValue(JsonNode metricNode, DailyMetricDataType type) {
        return switch (type) {
            case STEPS -> metricNode.path("count").asInt();
            case RESTING_HEART_RATE -> metricNode.path("beatsPerMinute").asInt();
            case HRV -> (int) Math.round(
                metricNode.path("averageHeartRateVariabilityMilliseconds").asDouble());
            case SLEEP -> metricNode.path("durationMinutes").asInt();
        };
    }

    // Daily metrics are bucketed by calendar day. The daily roll-up types
    // carry the day as a nested {year,month,day} `date` object inside the
    // metric node (verified for resting HR and HRV). Fall back, in order, to
    // an interval start, a sample physical time, then the data point's
    // updateTime, to stay robust against per-type shape differences (e.g.
    // steps/sleep, whose real shapes are still to be confirmed).
    private static LocalDate parseDate(JsonNode metricNode, JsonNode dataPoint) {
        JsonNode date = metricNode.path("date");
        if (date.hasNonNull("year")) {
            return LocalDate.of(
                date.path("year").asInt(),
                date.path("month").asInt(),
                date.path("day").asInt());
        }
        String intervalStart = metricNode.path("interval").path("startTime").asText("");
        if (!intervalStart.isEmpty()) {
            return Instant.parse(intervalStart).atZone(ZoneOffset.UTC).toLocalDate();
        }
        String physical = metricNode.path("sampleTime").path("physicalTime").asText("");
        if (!physical.isEmpty()) {
            return Instant.parse(physical).atZone(ZoneOffset.UTC).toLocalDate();
        }
        String update = dataPoint.path("updateTime").asText("");
        if (!update.isEmpty()) {
            return Instant.parse(update).atZone(ZoneOffset.UTC).toLocalDate();
        }
        return Instant.EPOCH.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
