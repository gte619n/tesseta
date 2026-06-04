package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DailyMetricDataTypeTest {

    @Test
    void fromApiNameMatchesUrlSegmentAndFilterFieldName() {
        assertThat(DailyMetricDataType.fromApiName("steps")).isEqualTo(DailyMetricDataType.STEPS);
        assertThat(DailyMetricDataType.fromApiName("daily-resting-heart-rate"))
            .isEqualTo(DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(DailyMetricDataType.fromApiName("daily_resting_heart_rate"))
            .isEqualTo(DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(DailyMetricDataType.fromApiName("daily-heart-rate-variability"))
            .isEqualTo(DailyMetricDataType.HRV);
        assertThat(DailyMetricDataType.fromApiName("daily_heart_rate_variability"))
            .isEqualTo(DailyMetricDataType.HRV);
        assertThat(DailyMetricDataType.fromApiName("sleep")).isEqualTo(DailyMetricDataType.SLEEP);
    }

    @Test
    void doesNotMatchBarePerSampleHeartRateForms() {
        // The bare (non-"daily-") forms are per-sample types we don't model;
        // they must not resolve to the daily roll-up enum.
        assertThat(DailyMetricDataType.tryFromApiName("resting-heart-rate")).isEmpty();
        assertThat(DailyMetricDataType.tryFromApiName("heart-rate-variability")).isEmpty();
    }

    @Test
    void tryFromApiNameReturnsEmptyForBodyCompositionTypes() {
        // Body-comp types must not be claimed by the daily-metric router.
        assertThat(DailyMetricDataType.tryFromApiName("weight")).isEmpty();
        assertThat(DailyMetricDataType.tryFromApiName("body-fat")).isEmpty();
        assertThat(DailyMetricDataType.tryFromApiName("garbage")).isEmpty();
    }

    @Test
    void fromApiNameRejectsUnknown() {
        assertThatThrownBy(() -> DailyMetricDataType.fromApiName("garbage"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
