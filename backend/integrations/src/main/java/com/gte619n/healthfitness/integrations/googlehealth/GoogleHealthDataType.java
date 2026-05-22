package com.gte619n.healthfitness.integrations.googlehealth;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;

// Maps our internal metric enum to Google Health API data-type identifiers.
// REST uses kebab-case in the URL path (.../dataTypes/body-fat/dataPoints).
// Filter expressions use camelCase for the data-type prefix and snake_case
// for the rest (bodyFat.sample_time.physical_time).
public enum GoogleHealthDataType {
    WEIGHT("weight", "weight"),
    BODY_FAT("body-fat", "bodyFat"),
    LEAN_MASS("lean-mass", "leanMass"),
    BMI("bmi", "bmi");

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
            case LEAN_MASS_KG -> LEAN_MASS;
            case BMI -> BMI;
        };
    }

    public BodyCompositionMetric toMetric() {
        return switch (this) {
            case WEIGHT -> BodyCompositionMetric.WEIGHT_KG;
            case BODY_FAT -> BodyCompositionMetric.BODY_FAT_PERCENT;
            case LEAN_MASS -> BodyCompositionMetric.LEAN_MASS_KG;
            case BMI -> BodyCompositionMetric.BMI;
        };
    }

    public static GoogleHealthDataType fromApiName(String apiName) {
        for (GoogleHealthDataType t : values()) {
            if (t.urlSegment.equals(apiName) || t.filterFieldName.equals(apiName)) {
                return t;
            }
        }
        // Legacy snake_case names — kept for back-compat in case Google
        // sends the older form in webhook notifications.
        return switch (apiName) {
            case "body_fat" -> BODY_FAT;
            case "lean_mass" -> LEAN_MASS;
            default -> throw new IllegalArgumentException(
                "Unknown Google Health data type: " + apiName);
        };
    }
}
