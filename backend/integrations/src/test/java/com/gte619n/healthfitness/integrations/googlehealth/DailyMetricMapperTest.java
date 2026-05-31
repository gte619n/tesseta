package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyMetricMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void mapsStepsCountAndDate() {
        JsonNode dp = node("""
            { "name": "users/h1/dataTypes/steps/dataPoints/r1",
              "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
              "steps": { "count": 9321,
                "sampleTime": { "physicalTime": "2026-05-20T23:59:00Z" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.STEPS);
        assertThat(p.healthUserId()).isEqualTo("h1");
        assertThat(p.recordId()).isEqualTo("r1");
        assertThat(p.type()).isEqualTo(DailyMetricDataType.STEPS);
        assertThat(p.value()).isEqualTo(9321);
        assertThat(p.date()).isEqualTo(LocalDate.parse("2026-05-20"));
        assertThat(p.sleepScore()).isNull();
        assertThat(p.sourcePlatform()).isEqualTo("FITBIT");
    }

    @Test
    void mapsRestingHeartRateBpm() {
        JsonNode dp = node("""
            { "name": "users/h1/dataTypes/resting-heart-rate/dataPoints/r2",
              "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
              "restingHeartRate": { "bpm": 54,
                "sampleTime": { "physicalTime": "2026-05-20T08:00:00Z" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(p.value()).isEqualTo(54);
        assertThat(p.sleepScore()).isNull();
    }

    @Test
    void mapsHrvMilliseconds() {
        JsonNode dp = node("""
            { "name": "users/h1/dataTypes/heart-rate-variability/dataPoints/r3",
              "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
              "heartRateVariability": { "milliseconds": 62,
                "sampleTime": { "physicalTime": "2026-05-20T08:00:00Z" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.HRV);
        assertThat(p.value()).isEqualTo(62);
    }

    @Test
    void mapsSleepDurationAndScore() {
        JsonNode dp = node("""
            { "name": "users/h1/dataTypes/sleep/dataPoints/r4",
              "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
              "sleep": { "durationMinutes": 462, "score": 88,
                "sampleTime": { "physicalTime": "2026-05-20T06:30:00Z" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.SLEEP);
        assertThat(p.value()).isEqualTo(462);
        assertThat(p.sleepScore()).isEqualTo(88);
    }

    @Test
    void fallsBackToUpdateTimeForDate_whenSampleTimeMissing() {
        JsonNode dp = node("""
            { "name": "users/h1/dataTypes/steps/dataPoints/r5",
              "updateTime": "2026-05-19T12:00:00Z",
              "dataSource": { "platform": "FITBIT" },
              "steps": { "count": 100 } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.STEPS);
        assertThat(p.date()).isEqualTo(LocalDate.parse("2026-05-19"));
        assertThat(p.recordingMethod()).isEqualTo("UNKNOWN");
    }
}
