package com.gte619n.healthfitness.integrations.googlehealth;

import java.util.Optional;

// Maps the daily-activity / vitals metrics a wearable (e.g. the Fitbit Air)
// reports through the Google Health API to their API data-type identifiers.
//
// Same three-form naming convention as GoogleHealthDataType:
//   URL path:            kebab-case  ("daily-resting-heart-rate")
//   Filter field prefix: snake_case  ("daily_resting_heart_rate")
//   JSON response key:   camelCase   ("dailyRestingHeartRate", used by the mapper)
//
// These land on the DailyMetric record (users/{userId}/dailyMetrics/
// {yyyy-MM-dd}) as one value per calendar day, but they reach that shape
// differently in the API (see GoogleHealthClient.listDailyMetricPoints):
//   - RESTING_HEART_RATE, HRV: genuine daily roll-up data types — listed and
//     filtered on their civil `date` member.
//   - STEPS: a per-minute interval type with no daily form; aggregated to a
//     daily total via the :dailyRollUp endpoint (countSum).
//   - SLEEP: a session type (stage list); :dailyRollUp is unsupported and no
//     sleep score is exposed, so it is not yet ingested.
//
// IMPORTANT: the Google Health API names daily roll-ups with a "daily-"
// prefix (daily-resting-heart-rate, daily-heart-rate-variability). The bare
// "resting-heart-rate" / "heart-rate-variability" forms are the per-sample
// types, which we do NOT subscribe to — a daily DailyMetric value is the
// roll-up. Routing on the bare forms silently drops every notification, so
// these strings must match the daily roll-up identifiers exactly.
public enum DailyMetricDataType {
    STEPS("steps", "steps", "steps"),
    RESTING_HEART_RATE(
        "daily-resting-heart-rate", "daily_resting_heart_rate", "dailyRestingHeartRate"),
    HRV("daily-heart-rate-variability", "daily_heart_rate_variability", "dailyHeartRateVariability"),
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
