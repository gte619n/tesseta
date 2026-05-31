package com.gte619n.healthfitness.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class WebhookHandlerServiceTest {

    private UserRepository users;
    private BodyCompositionRepository measurements;
    private DeviceSyncRepository deviceSyncs;
    private AccessTokenService tokens;
    private GoogleHealthClient googleHealth;
    private MetricChangedPublisher metricChangedPublisher;
    private WebhookHandlerService handler;

    private static final Instant FROM = Instant.parse("2026-05-22T07:45:00Z");
    private static final Instant TO = Instant.parse("2026-05-22T07:45:01Z");

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        measurements = Mockito.mock(BodyCompositionRepository.class);
        deviceSyncs = Mockito.mock(DeviceSyncRepository.class);
        tokens = Mockito.mock(AccessTokenService.class);
        googleHealth = Mockito.mock(GoogleHealthClient.class);
        metricChangedPublisher = Mockito.mock(MetricChangedPublisher.class);
        handler = new WebhookHandlerService(users, measurements, deviceSyncs, tokens, googleHealth, metricChangedPublisher);

        when(users.findByHealthUserId("h-1")).thenReturn(Optional.of(
            new User("u-1", "u@example.com", "U", null, null, Instant.EPOCH, Instant.EPOCH)));
        when(tokens.accessTokenFor("u-1")).thenReturn("test-access-token");
    }

    @Test
    void weightUpsertSavesWeightOnly() {
        // lean-mass and BMI aren't queryable through the Google Health API,
        // so no bonus pulls happen on weight notifications.
        when(googleHealth.listDataPoints(anyString(), eq(GoogleHealthDataType.WEIGHT), eq(FROM), eq(TO)))
            .thenReturn(List.of(point("w-1", GoogleHealthDataType.WEIGHT, 82.4)));

        handler.handle(new WebhookHandlerService.Notification(
            "h-1", GoogleHealthDataType.WEIGHT,
            WebhookHandlerService.Operation.UPSERT, FROM, TO));

        verify(googleHealth).listDataPoints(anyString(), eq(GoogleHealthDataType.WEIGHT), eq(FROM), eq(TO));

        ArgumentCaptor<List<BodyCompositionMeasurement>> captor = saveCaptor();
        verify(measurements).saveAll(captor.capture());
        assertThat(captor.getValue())
            .extracting(BodyCompositionMeasurement::metric)
            .containsExactly(BodyCompositionMetric.WEIGHT_KG);

        // The ingested data point's source platform is recorded as a device sync
        // so the clients can show device freshness.
        verify(deviceSyncs).recordSync(eq("u-1"), eq("FITBIT"), any());
    }

    @Test
    void bodyFatUpsertSavesBodyFatOnly() {
        when(googleHealth.listDataPoints(anyString(), eq(GoogleHealthDataType.BODY_FAT), eq(FROM), eq(TO)))
            .thenReturn(List.of(point("bf-1", GoogleHealthDataType.BODY_FAT, 18.5)));

        handler.handle(new WebhookHandlerService.Notification(
            "h-1", GoogleHealthDataType.BODY_FAT,
            WebhookHandlerService.Operation.UPSERT, FROM, TO));

        verify(googleHealth).listDataPoints(anyString(), eq(GoogleHealthDataType.BODY_FAT), eq(FROM), eq(TO));
    }

    @Test
    void deleteDoesNotHitTheRestClient() {
        handler.handle(new WebhookHandlerService.Notification(
            "h-1", GoogleHealthDataType.WEIGHT,
            WebhookHandlerService.Operation.DELETE, FROM, TO));

        verify(measurements).deleteByUserMetricAndRange(
            "u-1", BodyCompositionMetric.WEIGHT_KG, FROM, TO);
        verify(googleHealth, never()).listDataPoints(anyString(), any(), any(), any());
    }

    @Test
    void unknownHealthUserIdIsDroppedSilently() {
        when(users.findByHealthUserId("h-unknown")).thenReturn(Optional.empty());

        handler.handle(new WebhookHandlerService.Notification(
            "h-unknown", GoogleHealthDataType.WEIGHT,
            WebhookHandlerService.Operation.UPSERT, FROM, TO));

        verify(googleHealth, never()).listDataPoints(anyString(), any(), any(), any());
        verify(measurements, never()).saveAll(any());
    }

    private static GoogleHealthDataPoint point(String recordId, GoogleHealthDataType type, double value) {
        return new GoogleHealthDataPoint(
            "users/h-1/dataTypes/" + type.urlSegment() + "/dataPoints/" + recordId,
            "h-1",
            recordId,
            type,
            value,
            FROM,
            "FITBIT",
            "AUTOMATIC"
        );
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<BodyCompositionMeasurement>> saveCaptor() {
        return ArgumentCaptor.forClass((Class) List.class);
    }
}
