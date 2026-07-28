package com.gte619n.healthfitness.api.googlehealth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class BackfillServiceTest {

    private BodyCompositionRepository measurements;
    private DeviceSyncRepository deviceSyncs;
    private AccessTokenService tokens;
    private GoogleHealthClient googleHealth;
    private SyncChangeNotifier syncNotifier;
    private BackfillService service;

    @BeforeEach
    void setUp() {
        measurements = Mockito.mock(BodyCompositionRepository.class);
        deviceSyncs = Mockito.mock(DeviceSyncRepository.class);
        tokens = Mockito.mock(AccessTokenService.class);
        googleHealth = Mockito.mock(GoogleHealthClient.class);
        syncNotifier = Mockito.mock(SyncChangeNotifier.class);
        // Small window (1 day, single chunk) so each data type queries once.
        service = new BackfillService(measurements, deviceSyncs, tokens, googleHealth, syncNotifier, 1, 365);
        when(tokens.accessTokenFor("u-1")).thenReturn("at");
    }

    @Test
    void notifiesDevicesWhenMeasurementsStored() {
        when(googleHealth.listDataPoints(anyString(), any(), any(), any()))
            .thenReturn(List.of(point("w-1", GoogleHealthDataType.WEIGHT, 82.4)));

        service.runBackfill("u-1");

        // Server-originated import must fan out to ALL devices (originDeviceId=null)
        // so the dashboard refreshes now instead of on the next periodic sync.
        verify(syncNotifier).changed(eq("u-1"), isNull(), eq("bodyComposition"));
    }

    @Test
    void doesNotNotifyWhenNothingStored() {
        when(googleHealth.listDataPoints(anyString(), any(), any(), any()))
            .thenReturn(List.of());

        service.runBackfill("u-1");

        verify(syncNotifier, never()).changed(anyString(), any(), any());
    }

    private static GoogleHealthDataPoint point(String recordId, GoogleHealthDataType type, double value) {
        return new GoogleHealthDataPoint(
            "users/h-1/dataTypes/" + type.urlSegment() + "/dataPoints/" + recordId,
            "h-1",
            recordId,
            type,
            value,
            Instant.parse("2026-05-22T07:45:00Z"),
            "FITBIT",
            "AUTOMATIC"
        );
    }
}
