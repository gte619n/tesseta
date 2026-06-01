package com.gte619n.healthfitness.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DailyMetricWebhookHandlerTest {

    private UserRepository users;
    private DailyMetricRepository dailyMetrics;
    private DeviceSyncRepository deviceSyncs;
    private AccessTokenService tokens;
    private GoogleHealthClient googleHealth;
    private MetricChangedPublisher metricChangedPublisher;
    private DailyMetricWebhookHandler handler;

    private static final Instant FROM = Instant.parse("2026-05-20T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-21T00:00:00Z");

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        dailyMetrics = Mockito.mock(DailyMetricRepository.class);
        deviceSyncs = Mockito.mock(DeviceSyncRepository.class);
        tokens = Mockito.mock(AccessTokenService.class);
        googleHealth = Mockito.mock(GoogleHealthClient.class);
        metricChangedPublisher = Mockito.mock(MetricChangedPublisher.class);
        handler = new DailyMetricWebhookHandler(
            users, dailyMetrics, deviceSyncs, tokens, googleHealth, metricChangedPublisher);

        when(users.findByHealthUserId("h-1")).thenReturn(Optional.of(
            new User("u-1", "u@example.com", "U", null, null, Instant.EPOCH, Instant.EPOCH)));
        when(tokens.accessTokenFor("u-1")).thenReturn("test-access-token");
    }

    @Test
    void stepsUpsertSavesDailyMetricAndRecordsDeviceSync() {
        when(googleHealth.listDailyMetricPoints(anyString(), eq(DailyMetricDataType.STEPS), eq(FROM), eq(TO)))
            .thenReturn(List.of(point("s1", DailyMetricDataType.STEPS, 9000, null)));

        handler.handle(new DailyMetricWebhookHandler.Notification(
            "h-1", DailyMetricDataType.STEPS,
            DailyMetricWebhookHandler.Operation.UPSERT, FROM, TO));

        ArgumentCaptor<DailyMetric> captor = ArgumentCaptor.forClass(DailyMetric.class);
        verify(dailyMetrics).save(captor.capture());
        assertThat(captor.getValue().steps()).isEqualTo(9000);
        assertThat(captor.getValue().restingHeartRate()).isNull();
        verify(deviceSyncs).recordSync(eq("u-1"), eq("FITBIT"), any());
        // Steps has no Goals metric key — nothing published.
        verify(metricChangedPublisher, never()).publish(anyString(), any());
    }

    @Test
    void sleepUpsertPublishesSleepScoreMetricKey() {
        when(googleHealth.listDailyMetricPoints(anyString(), eq(DailyMetricDataType.SLEEP), eq(FROM), eq(TO)))
            .thenReturn(List.of(point("sl1", DailyMetricDataType.SLEEP, 460, 88)));

        handler.handle(new DailyMetricWebhookHandler.Notification(
            "h-1", DailyMetricDataType.SLEEP,
            DailyMetricWebhookHandler.Operation.UPSERT, FROM, TO));

        ArgumentCaptor<DailyMetric> captor = ArgumentCaptor.forClass(DailyMetric.class);
        verify(dailyMetrics).save(captor.capture());
        assertThat(captor.getValue().sleepMinutes()).isEqualTo(460);
        assertThat(captor.getValue().sleepScore()).isEqualTo(88);
        verify(metricChangedPublisher).publish("u-1", MetricKey.VITALS_SLEEP_SCORE);
    }

    @Test
    void deleteIsIgnored() {
        handler.handle(new DailyMetricWebhookHandler.Notification(
            "h-1", DailyMetricDataType.STEPS,
            DailyMetricWebhookHandler.Operation.DELETE, FROM, TO));

        verify(googleHealth, never()).listDailyMetricPoints(anyString(), any(), any(), any());
        verify(dailyMetrics, never()).save(any());
    }

    @Test
    void unknownHealthUserIdIsDroppedSilently() {
        when(users.findByHealthUserId("h-x")).thenReturn(Optional.empty());
        handler.handle(new DailyMetricWebhookHandler.Notification(
            "h-x", DailyMetricDataType.STEPS,
            DailyMetricWebhookHandler.Operation.UPSERT, FROM, TO));
        verify(googleHealth, never()).listDailyMetricPoints(anyString(), any(), any(), any());
    }

    private static DailyMetricDataPoint point(String recordId, DailyMetricDataType type, int value, Integer sleepScore) {
        return new DailyMetricDataPoint(
            "users/h-1/dataTypes/" + type.urlSegment() + "/dataPoints/" + recordId,
            "h-1",
            recordId,
            type,
            LocalDate.parse("2026-05-20"),
            value,
            sleepScore,
            "FITBIT",
            "AUTOMATIC"
        );
    }
}
