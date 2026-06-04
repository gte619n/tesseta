package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BodyCompositionMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesWeightDataPoint() throws Exception {
        // Google Health stores weight in grams. Mapper converts to kg.
        JsonNode json = mapper.readTree("""
            {
              "name": "users/2515055256096816351/dataTypes/weight/dataPoints/8896720705097069096",
              "dataSource": { "recordingMethod": "AUTOMATIC", "platform": "FITBIT" },
              "weight": {
                "weightGrams": 82400,
                "sampleTime": { "physicalTime": "2026-05-20T07:45:00Z" }
              },
              "updateTime": "2026-05-20T07:45:01Z"
            }
            """);

        GoogleHealthDataPoint point = BodyCompositionMapper.fromJson(json, GoogleHealthDataType.WEIGHT);

        assertThat(point.healthUserId()).isEqualTo("2515055256096816351");
        assertThat(point.recordId()).isEqualTo("8896720705097069096");
        assertThat(point.dataType()).isEqualTo(GoogleHealthDataType.WEIGHT);
        assertThat(point.value()).isEqualTo(82.4);
        assertThat(point.sampleTime()).isEqualTo(Instant.parse("2026-05-20T07:45:00Z"));
        assertThat(point.sourcePlatform()).isEqualTo("FITBIT");
        assertThat(point.recordingMethod()).isEqualTo("AUTOMATIC");
    }

    @Test
    void parsesBodyFatPercentage() throws Exception {
        JsonNode json = mapper.readTree("""
            {
              "name": "users/abc/dataTypes/body-fat/dataPoints/xyz",
              "dataSource": { "recordingMethod": "MANUAL", "platform": "WITHINGS" },
              "bodyFat": {
                "percentage": 18.7,
                "sampleTime": { "physicalTime": "2026-05-20T08:00:00Z" }
              }
            }
            """);

        GoogleHealthDataPoint point = BodyCompositionMapper.fromJson(json, GoogleHealthDataType.BODY_FAT);
        assertThat(point.value()).isEqualTo(18.7);
        assertThat(point.dataType()).isEqualTo(GoogleHealthDataType.BODY_FAT);
    }

    // lean-mass and BMI aren't standalone data types in the v4 Google
    // Health API (INVALID_PARENT_DATA_TYPE_COLLECTION), so the mapper
    // doesn't need to handle them.

    @Test
    void fallsBackToUpdateTimeWhenSampleTimeMissing() throws Exception {
        JsonNode json = mapper.readTree("""
            { "name": "users/u/dataTypes/weight/dataPoints/r",
              "dataSource": { "platform": "FITBIT" },
              "weight": { "weightGrams": 80000 },
              "updateTime": "2026-05-20T07:45:01Z" }
            """);
        GoogleHealthDataPoint point = BodyCompositionMapper.fromJson(json, GoogleHealthDataType.WEIGHT);
        assertThat(point.sampleTime()).isEqualTo(Instant.parse("2026-05-20T07:45:01Z"));
    }

    @Test
    void resourceNameParserExtractsHealthUserIdAndRecordId() {
        String name = "users/12345/dataTypes/weight/dataPoints/abc";
        assertThat(BodyCompositionMapper.parseHealthUserId(name)).isEqualTo("12345");
        assertThat(BodyCompositionMapper.parseRecordId(name)).isEqualTo("abc");
    }

    @Test
    void resourceNameParserRejectsMalformed() {
        assertThatThrownBy(() -> BodyCompositionMapper.parseHealthUserId("garbage"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BodyCompositionMapper.parseRecordId("users/12345/wrong"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
