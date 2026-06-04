package com.gte619n.healthfitness.integrations.googlehealth;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

// Translates a raw Google Health dataPoint JSON node into our
// GoogleHealthDataPoint record, and from there into a
// BodyCompositionMeasurement (done in WebhookHandlerService / BackfillService
// where the app userId is known).
public final class BodyCompositionMapper {

    private BodyCompositionMapper() {}

    public static GoogleHealthDataPoint fromJson(JsonNode dataPoint, GoogleHealthDataType dataType) {
        String name = dataPoint.path("name").asText();
        String healthUserId = parseHealthUserId(name);
        String recordId = parseRecordId(name);

        JsonNode metricNode = dataPoint.path(jsonFieldFor(dataType));
        double value = extractValue(metricNode, dataType);

        // sampleTime lives under {metric}.sampleTime.physicalTime in Google's
        // current response shape; fall back gracefully if it shifts.
        Instant sampleTime = parseInstant(
            metricNode.path("sampleTime").path("physicalTime").asText(""),
            dataPoint.path("updateTime").asText("")
        );

        JsonNode dataSource = dataPoint.path("dataSource");
        String platform = dataSource.path("platform").asText("UNKNOWN");
        String method = dataSource.path("recordingMethod").asText("UNKNOWN");

        return new GoogleHealthDataPoint(
            name,
            healthUserId,
            recordId,
            dataType,
            value,
            sampleTime,
            platform,
            method
        );
    }

    // The JSON field on the dataPoint that carries the value object differs
    // by data type. Google uses camelCase here (not snake_case like the filter
    // field name).
    private static String jsonFieldFor(GoogleHealthDataType dataType) {
        return switch (dataType) {
            case WEIGHT -> "weight";
            case BODY_FAT -> "bodyFat";
        };
    }

    private static double extractValue(JsonNode metricNode, GoogleHealthDataType dataType) {
        return switch (dataType) {
            // Google Health stores weight as `weightGrams` (an int field in
            // grams) inside the weight object. Convert to canonical kg.
            case WEIGHT -> metricNode.path("weightGrams").asDouble() / 1000.0;
            // bodyFat exposes a `percentage` decimal field directly.
            case BODY_FAT -> metricNode.path("percentage").asDouble();
        };
    }

    static String parseHealthUserId(String resourceName) {
        // Format: users/{healthUserId}/dataTypes/{type}/dataPoints/{recordId}
        int usersStart = resourceName.indexOf("users/");
        int dataTypesStart = resourceName.indexOf("/dataTypes/");
        if (usersStart < 0 || dataTypesStart < 0) {
            throw new IllegalArgumentException("Unexpected resource name: " + resourceName);
        }
        return resourceName.substring(usersStart + "users/".length(), dataTypesStart);
    }

    static String parseRecordId(String resourceName) {
        int dataPointsTag = resourceName.lastIndexOf("/dataPoints/");
        if (dataPointsTag < 0) {
            throw new IllegalArgumentException("Unexpected resource name: " + resourceName);
        }
        return resourceName.substring(dataPointsTag + "/dataPoints/".length());
    }

    private static Instant parseInstant(String primary, String fallback) {
        if (!primary.isEmpty()) {
            return Instant.parse(primary);
        }
        if (!fallback.isEmpty()) {
            return Instant.parse(fallback);
        }
        return Instant.EPOCH;
    }
}
