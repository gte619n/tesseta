package com.gte619n.healthfitness.integrations.googlehealth;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import java.util.Optional;

// Maps our internal metric enum to Google Health API data-type identifiers.
// URL path: kebab-case ("body-fat").
// Filter prefix: snake_case ("body_fat").
// JSON response object key: camelCase ("bodyFat", handled by the mapper).
// All three are documented and confirmed against live error responses.
//
// Only WEIGHT and BODY_FAT are present here — the v4 Google Health API
// does NOT expose lean-mass or BMI as queryable data types
// (INVALID_PARENT_DATA_TYPE_COLLECTION at /dataTypes/lean-mass/dataPoints
// confirms this). The BodyCompositionMetric enum keeps LEAN_MASS_KG and
// BMI as domain concepts so we can compute them ourselves later from
// weight + height.
public enum GoogleHealthDataType {
    WEIGHT("weight", "weight"),
    BODY_FAT("body-fat", "body_fat");

    private final String urlSegment;
    private final String filterFieldName;

    GoogleHealthDataType(String urlSegment, String filterFieldName) {
        this.urlSegment = urlSegment;
        this.filterFieldName = filterFieldName;
    }

    public String urlSegment() {
        return urlSegment;
    }

    public String filterFieldName() {
        return filterFieldName;
    }

    public static GoogleHealthDataType forMetric(BodyCompositionMetric metric) {
        return switch (metric) {
            case WEIGHT_KG -> WEIGHT;
            case BODY_FAT_PERCENT -> BODY_FAT;
            case LEAN_MASS_KG, BMI -> throw new IllegalArgumentException(
                metric + " is not queryable through the Google Health API");
        };
    }

    public BodyCompositionMetric toMetric() {
        return switch (this) {
            case WEIGHT -> BodyCompositionMetric.WEIGHT_KG;
            case BODY_FAT -> BodyCompositionMetric.BODY_FAT_PERCENT;
        };
    }

    public static GoogleHealthDataType fromApiName(String apiName) {
        return tryFromApiName(apiName).orElseThrow(() ->
            new IllegalArgumentException("Unknown Google Health data type: " + apiName));
    }

    // Non-throwing lookup, used by the webhook controller to route a raw
    // dataType string without using exceptions for control flow (a string
    // may instead be a daily-metric type).
    public static Optional<GoogleHealthDataType> tryFromApiName(String apiName) {
        for (GoogleHealthDataType t : values()) {
            if (t.urlSegment.equals(apiName) || t.filterFieldName.equals(apiName)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
