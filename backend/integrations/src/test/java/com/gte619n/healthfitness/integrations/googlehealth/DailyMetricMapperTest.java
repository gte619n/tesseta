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

    // Resting HR + HRV fixtures below are VERBATIM real API responses captured
    // from health.googleapis.com via the admin sync/inspect endpoint: nested
    // {year,month,day} date, string-encoded int64 bpm, double HRV, and NO
    // resource `name`.
    @Test
    void mapsRestingHeartRateFromRealPayload() {
        JsonNode dp = node("""
            { "dataSource": { "recordingMethod": "DERIVED", "device": {}, "platform": "FITBIT" },
              "dailyRestingHeartRate": {
                "date": { "year": 2026, "month": 6, "day": 1 },
                "beatsPerMinute": "66",
                "dailyRestingHeartRateMetadata": { "calculationMethod": "WITH_SLEEP" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(p.value()).isEqualTo(66);
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(p.sourcePlatform()).isEqualTo("FITBIT");
        assertThat(p.recordingMethod()).isEqualTo("DERIVED");
        assertThat(p.sleepScore()).isNull();
        // No resource name in the payload — identity is synthesized per (type, day).
        assertThat(p.healthUserId()).isNull();
        assertThat(p.recordId()).isEqualTo("daily-resting-heart-rate:2026-06-01");
    }

    @Test
    void mapsHrvFromRealPayload_roundsDoubleMilliseconds() {
        JsonNode dp = node("""
            { "dataSource": { "recordingMethod": "DERIVED", "device": {}, "platform": "FITBIT" },
              "dailyHeartRateVariability": {
                "date": { "year": 2026, "month": 6, "day": 1 },
                "averageHeartRateVariabilityMilliseconds": 21.75,
                "nonRemHeartRateBeatsPerMinute": "61",
                "entropy": 2.951,
                "deepSleepRootMeanSquareOfSuccessiveDifferencesMilliseconds": 23.9 } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.HRV);
        assertThat(p.value()).isEqualTo(22); // 21.75 rounded
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(p.recordId()).isEqualTo("daily-heart-rate-variability:2026-06-01");
    }

    // Steps/sleep real shapes are still unverified (their reads were blocked by
    // missing OAuth scopes). These fixtures exercise the value field + the
    // fallback date paths; revisit once a real steps/sleep payload is captured.
    @Test
    void mapsStepsCountWithIntervalDate() {
        JsonNode dp = node("""
            { "dataSource": { "platform": "FITBIT", "recordingMethod": "AUTOMATIC" },
              "steps": { "count": 9321,
                "interval": { "startTime": "2026-05-20T00:00:00Z" } } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.STEPS);
        assertThat(p.value()).isEqualTo(9321);
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(p.recordId()).isEqualTo("steps:2026-05-20");
    }

    @Test
    void fallsBackToUpdateTimeForDate_whenNoDateOrInterval() {
        JsonNode dp = node("""
            { "updateTime": "2026-05-19T12:00:00Z",
              "dataSource": { "platform": "FITBIT" },
              "steps": { "count": 100 } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.STEPS);
        assertThat(p.date()).isEqualTo(LocalDate.of(2026, 5, 19));
        assertThat(p.recordingMethod()).isEqualTo("UNKNOWN");
    }

    @Test
    void doesNotThrowWhenResourceNameAbsent() {
        // The real daily payloads have no `name`; the mapper must not crash
        // (the previous resource-name parsing threw on every real point).
        JsonNode dp = node("""
            { "dataSource": { "platform": "FITBIT" },
              "dailyRestingHeartRate": {
                "date": { "year": 2026, "month": 6, "day": 1 }, "beatsPerMinute": "60" } }
            """);
        DailyMetricDataPoint p = DailyMetricMapper.fromJson(dp, DailyMetricDataType.RESTING_HEART_RATE);
        assertThat(p.value()).isEqualTo(60);
        assertThat(p.name()).isNull();
    }
}
