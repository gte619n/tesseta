package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DailyMetricDataTypeTest {

    @Test
    void fromApiNameMatchesUrlSegmentAndFilterFieldName() {
        assertThat(DailyMetricDataType.fromApiName("steps")).isEqualTo(DailyMetricDataType.STEPS);
        assertThat(DailyMetricDataType.fromApiName("resting-heart-rate"))
            .isEqualTo(DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(DailyMetricDataType.fromApiName("resting_heart_rate"))
            .isEqualTo(DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(DailyMetricDataType.fromApiName("heart-rate-variability"))
            .isEqualTo(DailyMetricDataType.HRV);
        assertThat(DailyMetricDataType.fromApiName("sleep")).isEqualTo(DailyMetricDataType.SLEEP);
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
