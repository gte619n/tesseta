package com.gte619n.healthfitness.api.withings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.withings.WithingsApiClient;
import com.gte619n.healthfitness.integrations.withings.WithingsBodyPoint;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class WithingsSyncServiceTest {

    private WithingsAccessTokenService tokens;
    private WithingsApiClient api;
    private DailyMetricRepository dailyMetrics;
    private BodyCompositionRepository measurements;
    private DeviceSyncRepository deviceSyncs;
    private SyncChangeNotifier syncNotifier;
    private MetricChangedPublisher metricChangedPublisher;
    private WithingsSyncService service;

    private final Instant from = Instant.parse("2026-09-01T00:00:00Z");
    private final Instant to = Instant.parse("2026-09-02T00:00:00Z");

    @BeforeEach
    void setUp() {
        tokens = Mockito.mock(WithingsAccessTokenService.class);
        api = Mockito.mock(WithingsApiClient.class);
        dailyMetrics = Mockito.mock(DailyMetricRepository.class);
        measurements = Mockito.mock(BodyCompositionRepository.class);
        deviceSyncs = Mockito.mock(DeviceSyncRepository.class);
        syncNotifier = Mockito.mock(SyncChangeNotifier.class);
        metricChangedPublisher = Mockito.mock(MetricChangedPublisher.class);
        service = new WithingsSyncService(tokens, api, dailyMetrics, measurements,
            deviceSyncs, syncNotifier, metricChangedPublisher);
        when(tokens.accessTokenFor("u-1")).thenReturn("at");
    }

    @Test
    void importSleepSavesMetricsAndRecordsDeviceSync() {
        LocalDate day = LocalDate.of(2026, 9, 1);
        when(api.listSleepMetrics("at", from, to)).thenReturn(List.of(
            new DailyMetricDataPoint(null, null, "withings:sleep:" + day,
                DailyMetricDataType.SLEEP, day, 450, 82, "WITHINGS", "AUTOMATIC"),
            new DailyMetricDataPoint(null, null, "withings:resting-hr:" + day,
                DailyMetricDataType.RESTING_HEART_RATE, day, 52, null, "WITHINGS", "AUTOMATIC")));

        int stored = service.importSleep("u-1", from, to);

        assertThat(stored).isEqualTo(2);
        ArgumentCaptor<DailyMetric> saved = ArgumentCaptor.forClass(DailyMetric.class);
        verify(dailyMetrics, Mockito.times(2)).save(saved.capture());
        // The SLEEP point carries minutes + score; the HR point carries restingHr.
        assertThat(saved.getAllValues()).anySatisfy(m -> {
            assertThat(m.sleepMinutes()).isEqualTo(450);
            assertThat(m.sleepScore()).isEqualTo(82);
        });
        assertThat(saved.getAllValues()).anySatisfy(m ->
            assertThat(m.restingHeartRate()).isEqualTo(52));
        verify(deviceSyncs).recordSync(eq("u-1"), eq("WITHINGS"), any());
        verify(syncNotifier).changed("u-1", null, "dailyMetrics");
    }

    @Test
    void importBodySavesMeasurementsWithWithingsPlatform() {
        when(api.listBodyMeasurements("at", from, to)).thenReturn(List.of(
            new WithingsBodyPoint("111", BodyCompositionMetric.WEIGHT_KG, 80.7,
                Instant.parse("2026-09-01T07:00:00Z"), "AUTOMATIC"),
            new WithingsBodyPoint("111", BodyCompositionMetric.BODY_FAT_PERCENT, 18.3,
                Instant.parse("2026-09-01T07:00:00Z"), "AUTOMATIC")));

        int stored = service.importBody("u-1", from, to);

        assertThat(stored).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BodyCompositionMeasurement>> saved =
            ArgumentCaptor.forClass(List.class);
        verify(measurements).saveAll(saved.capture());
        assertThat(saved.getValue()).allMatch(m -> m.sourcePlatform().equals("WITHINGS")
            && m.userId().equals("u-1"));
        verify(syncNotifier).changed("u-1", null, "bodyComposition");
    }
}
