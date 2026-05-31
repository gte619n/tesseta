package com.gte619n.healthfitness.integrations.googlehealth;

import java.util.Optional;

// Maps the daily-activity / vitals metrics a wearable (e.g. the Fitbit Air)
// reports through the Google Health API to their API data-type identifiers.
//
// Same three-form naming convention as GoogleHealthDataType:
//   URL path:            kebab-case  ("resting-heart-rate")
//   Filter field prefix: snake_case  ("resting_heart_rate")
//   JSON response key:   camelCase   ("restingHeartRate", used by the mapper)
//
// Unlike body composition these are day-grained aggregates: one data point
// per calendar day per type. They land on the DailyMetric record
// (users/{userId}/dailyMetrics/{yyyy-MM-dd}) rather than body composition.
public enum DailyMetricDataType {
    STEPS("steps", "steps", "steps"),
    RESTING_HEART_RATE("resting-heart-rate", "resting_heart_rate", "restingHeartRate"),
    HRV("heart-rate-variability", "heart_rate_variability", "heartRateVariability"),
    SLEEP("sleep", "sleep", "sleep");

    private final String urlSegment;
    private final String filterFieldName;
    private final String jsonField;

    DailyMetricDataType(String urlSegment, String filterFieldName, String jsonField) {
        this.urlSegment = urlSegment;
        this.filterFieldName = filterFieldName;
        this.jsonField = jsonField;
    }

    public String urlSegment() {
        return urlSegment;
    }

    public String filterFieldName() {
        return filterFieldName;
    }

    public String jsonField() {
        return jsonField;
    }

    public static DailyMetricDataType fromApiName(String apiName) {
        return tryFromApiName(apiName).orElseThrow(() ->
            new IllegalArgumentException("Unknown daily-metric data type: " + apiName));
    }

    // Non-throwing lookup, used by the webhook controller to route a raw
    // dataType string to the right handler without exceptions for control
    // flow (a string may instead be a body-composition type).
    public static Optional<DailyMetricDataType> tryFromApiName(String apiName) {
        for (DailyMetricDataType t : values()) {
            if (t.urlSegment.equals(apiName) || t.filterFieldName.equals(apiName)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
