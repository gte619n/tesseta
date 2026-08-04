package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// One-time pull-everything-in-range after a user first connects. Runs
// asynchronously on a virtual thread so the /connect HTTP response can
// return immediately; webhook notifications cover the forward path.
@Service
public class BackfillService {

    private static final Logger log = LoggerFactory.getLogger(BackfillService.class);

    private final BodyCompositionRepository measurements;
    private final DeviceSyncRepository deviceSyncs;
    private final AccessTokenService tokens;
    private final GoogleHealthClient googleHealth;
    private final SyncChangeNotifier syncNotifier;
    private final int backfillDays;
    private final int chunkDays;

    public BackfillService(
        BodyCompositionRepository measurements,
        DeviceSyncRepository deviceSyncs,
        AccessTokenService tokens,
        GoogleHealthClient googleHealth,
        SyncChangeNotifier syncNotifier,
        @Value("${app.googlehealth.backfill-days:1460}") int backfillDays,
        @Value("${app.googlehealth.backfill-chunk-days:365}") int chunkDays
    ) {
        this.measurements = measurements;
        this.deviceSyncs = deviceSyncs;
        this.tokens = tokens;
        this.googleHealth = googleHealth;
        this.syncNotifier = syncNotifier;
        this.backfillDays = backfillDays;
        this.chunkDays = chunkDays;
    }

    public void scheduleBackfill(String userId) {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> runBackfill(userId));
    }

    // Full-history backfill run once when a user first connects.
    void runBackfill(String userId) {
        runBackfill(userId, backfillDays);
    }

    // Re-pull body-composition over a bounded recent window. Used both by the
    // full-history connect backfill (windowDays = backfillDays) and by the
    // periodic refresh job, which passes a short window as a safety net for
    // missed webhooks or a lapsed subscription. Returns the stored count.
    int runBackfill(String userId, int windowDays) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(windowDays));
        log.info("Backfill start user={} window=[{},{}]", userId, windowStart, now);
        int total = 0;
        Set<String> platforms = new LinkedHashSet<>();
        try {
            String accessToken = tokens.accessTokenFor(userId);
            for (GoogleHealthDataType type : GoogleHealthDataType.values()) {
                total += backfillType(userId, accessToken, type, windowStart, now, platforms);
            }
            for (String platform : platforms) {
                deviceSyncs.recordSync(userId, platform, now);
            }
            // Server-originated import (e.g. after a reconnect) — fan out to ALL
            // devices so the dashboard refreshes now instead of on the next
            // periodic sync. Matches the webhook path (WebhookHandlerService).
            if (total > 0) {
                syncNotifier.changed(userId, null, "bodyComposition");
            }
            log.info("Backfill complete user={} totalStored={}", userId, total);
        } catch (RuntimeException e) {
            log.error("Backfill failed user={} totalStored={} cause={}",
                userId, total, e.getMessage(), e);
        }
        return total;
    }

    private int backfillType(
        String userId,
        String accessToken,
        GoogleHealthDataType type,
        Instant windowStart,
        Instant windowEnd,
        Set<String> platforms
    ) {
        Instant cursor = windowStart;
        List<BodyCompositionMeasurement> all = new ArrayList<>();
        while (cursor.isBefore(windowEnd)) {
            Instant chunkEnd = cursor.plus(Duration.ofDays(chunkDays));
            if (chunkEnd.isAfter(windowEnd)) chunkEnd = windowEnd;
            List<GoogleHealthDataPoint> points = googleHealth.listDataPoints(
                accessToken, type, cursor, chunkEnd);
            for (GoogleHealthDataPoint dp : points) {
                all.add(new BodyCompositionMeasurement(
                    userId,
                    dp.recordId(),
                    dp.dataType().toMetric(),
                    dp.value(),
                    dp.sampleTime(),
                    dp.sourcePlatform(),
                    dp.recordingMethod(),
                    null,
                    null
                ));
                if (dp.sourcePlatform() != null && !dp.sourcePlatform().isBlank()) {
                    platforms.add(dp.sourcePlatform());
                }
            }
            cursor = chunkEnd;
        }
        measurements.saveAll(all);
        log.info("Backfill type={} user={} stored={}", type, userId, all.size());
        return all.size();
    }
}
