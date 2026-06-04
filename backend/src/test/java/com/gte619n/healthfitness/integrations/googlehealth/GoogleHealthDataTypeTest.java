package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import org.junit.jupiter.api.Test;

class GoogleHealthDataTypeTest {

    @Test
    void roundTripsQueryableMetrics() {
        // Only WEIGHT_KG and BODY_FAT_PERCENT are queryable; LEAN_MASS_KG
        // and BMI are domain placeholders we may compute later.
        assertThat(GoogleHealthDataType.forMetric(BodyCompositionMetric.WEIGHT_KG).toMetric())
            .isEqualTo(BodyCompositionMetric.WEIGHT_KG);
        assertThat(GoogleHealthDataType.forMetric(BodyCompositionMetric.BODY_FAT_PERCENT).toMetric())
            .isEqualTo(BodyCompositionMetric.BODY_FAT_PERCENT);
    }

    @Test
    void forMetricRejectsNonQueryableMetrics() {
        assertThatThrownBy(() -> GoogleHealthDataType.forMetric(BodyCompositionMetric.LEAN_MASS_KG))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not queryable");
        assertThatThrownBy(() -> GoogleHealthDataType.forMetric(BodyCompositionMetric.BMI))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fromApiNameMatchesUrlSegmentAndFilterFieldName() {
        assertThat(GoogleHealthDataType.fromApiName("weight")).isEqualTo(GoogleHealthDataType.WEIGHT);
        assertThat(GoogleHealthDataType.fromApiName("body-fat")).isEqualTo(GoogleHealthDataType.BODY_FAT);
        assertThat(GoogleHealthDataType.fromApiName("body_fat")).isEqualTo(GoogleHealthDataType.BODY_FAT);
    }

    @Test
    void fromApiNameRejectsUnknown() {
        assertThatThrownBy(() -> GoogleHealthDataType.fromApiName("garbage"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
