package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// One-time pull of day-grained activity / vitals metrics (steps, resting
// HR, HRV, sleep) after a user first connects, mirroring BackfillService for
// body composition. Runs on a virtual thread so /connect returns
// immediately; webhook notifications cover the forward path.
@Service
public class DailyMetricBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DailyMetricBackfillService.class);

    private final DailyMetricRepository dailyMetrics;
    private final DeviceSyncRepository deviceSyncs;
    private final AccessTokenService tokens;
    private final GoogleHealthClient googleHealth;
    private final int backfillDays;
    private final int chunkDays;

    public DailyMetricBackfillService(
        DailyMetricRepository dailyMetrics,
        DeviceSyncRepository deviceSyncs,
        AccessTokenService tokens,
        GoogleHealthClient googleHealth,
        @Value("${app.googlehealth.backfill-days:1460}") int backfillDays,
        @Value("${app.googlehealth.backfill-chunk-days:365}") int chunkDays
    ) {
        this.dailyMetrics = dailyMetrics;
        this.deviceSyncs = deviceSyncs;
        this.tokens = tokens;
        this.googleHealth = googleHealth;
        this.backfillDays = backfillDays;
        this.chunkDays = chunkDays;
    }

    public void scheduleBackfill(String userId) {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> runBackfill(userId));
    }

    void runBackfill(String userId) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(backfillDays));
        log.info("Daily-metric backfill start user={} window=[{},{}]", userId, windowStart, now);
        int total = 0;
        Set<String> platforms = new LinkedHashSet<>();
        try {
            String accessToken = tokens.accessTokenFor(userId);
            for (DailyMetricDataType type : DailyMetricDataType.values()) {
                total += backfillType(userId, accessToken, type, windowStart, now, platforms);
            }
            for (String platform : platforms) {
                deviceSyncs.recordSync(userId, platform, now);
            }
            log.info("Daily-metric backfill complete user={} totalStored={} platforms={}",
                userId, total, platforms);
        } catch (RuntimeException e) {
            log.error("Daily-metric backfill failed user={} totalStored={} cause={}",
                userId, total, e.getMessage(), e);
        }
    }

    private int backfillType(
        String userId,
        String accessToken,
        DailyMetricDataType type,
        Instant windowStart,
        Instant windowEnd,
        Set<String> platforms
    ) {
        Instant cursor = windowStart;
        int stored = 0;
        while (cursor.isBefore(windowEnd)) {
            Instant chunkEnd = cursor.plus(Duration.ofDays(chunkDays));
            if (chunkEnd.isAfter(windowEnd)) chunkEnd = windowEnd;
            for (DailyMetricDataPoint dp : googleHealth.listDailyMetricPoints(
                accessToken, type, cursor, chunkEnd)) {
                dailyMetrics.save(toDailyMetric(userId, dp));
                if (dp.sourcePlatform() != null && !dp.sourcePlatform().isBlank()) {
                    platforms.add(dp.sourcePlatform());
                }
                stored++;
            }
            cursor = chunkEnd;
        }
        log.info("Daily-metric backfill type={} user={} stored={}", type, userId, stored);
        return stored;
    }

    // Build a DailyMetric carrying only the field(s) this data point maps to;
    // the repository merges it into the day's document.
    static DailyMetric toDailyMetric(String userId, DailyMetricDataPoint dp) {
        Integer steps = dp.type() == DailyMetricDataType.STEPS ? dp.value() : null;
        Integer restingHr = dp.type() == DailyMetricDataType.RESTING_HEART_RATE ? dp.value() : null;
        Integer hrv = dp.type() == DailyMetricDataType.HRV ? dp.value() : null;
        Integer sleepMinutes = dp.type() == DailyMetricDataType.SLEEP ? dp.value() : null;
        Integer sleepScore = dp.type() == DailyMetricDataType.SLEEP ? dp.sleepScore() : null;
        return new DailyMetric(
            userId,
            dp.date(),
            steps,
            restingHr,
            sleepMinutes,
            hrv,
            sleepScore,
            null,
            null
        );
    }
}
