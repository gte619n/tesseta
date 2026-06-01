package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

// Translates a raw Google Health dataPoint JSON node for a daily-activity
// metric into a DailyMetricDataPoint. Reuses BodyCompositionMapper's
// resource-name parsing (same package) since the `name` path shape is
// identical across data types.
public final class DailyMetricMapper {

    private DailyMetricMapper() {}

    public static DailyMetricDataPoint fromJson(JsonNode dataPoint, DailyMetricDataType type) {
        String name = dataPoint.path("name").asText();
        String healthUserId = BodyCompositionMapper.parseHealthUserId(name);
        String recordId = BodyCompositionMapper.parseRecordId(name);

        JsonNode metricNode = dataPoint.path(type.jsonField());

        int value = extractValue(metricNode, type);
        Integer sleepScore = type == DailyMetricDataType.SLEEP && metricNode.has("score")
            ? metricNode.path("score").asInt()
            : null;

        LocalDate date = parseDate(
            metricNode.path("sampleTime").path("physicalTime").asText(""),
            dataPoint.path("updateTime").asText("")
        );

        JsonNode dataSource = dataPoint.path("dataSource");
        String platform = dataSource.path("platform").asText("UNKNOWN");
        String method = dataSource.path("recordingMethod").asText("UNKNOWN");

        return new DailyMetricDataPoint(
            name,
            healthUserId,
            recordId,
            type,
            date,
            value,
            sleepScore,
            platform,
            method
        );
    }

    private static int extractValue(JsonNode metricNode, DailyMetricDataType type) {
        return switch (type) {
            case STEPS -> metricNode.path("count").asInt();
            case RESTING_HEART_RATE -> metricNode.path("bpm").asInt();
            case HRV -> metricNode.path("milliseconds").asInt();
            case SLEEP -> metricNode.path("durationMinutes").asInt();
        };
    }

    // Daily metrics are bucketed by calendar day; we derive the day (UTC)
    // from the sample's physical time, falling back to updateTime.
    private static LocalDate parseDate(String primary, String fallback) {
        if (!primary.isEmpty()) {
            return Instant.parse(primary).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (!fallback.isEmpty()) {
            return Instant.parse(fallback).atZone(ZoneOffset.UTC).toLocalDate();
        }
        return Instant.EPOCH.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
