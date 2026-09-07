package com.gte619n.healthfitness.api.withings;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// One-time pull of a user's Withings history (sleep + body) after they first
// connect, mirroring the Google Health backfill services. Runs on a virtual
// thread so /connect returns immediately; webhook notifications cover the
// forward path and the refresh sweep is the safety net.
@Service
public class WithingsBackfillService {

    private static final Logger log = LoggerFactory.getLogger(WithingsBackfillService.class);

    private final WithingsSyncService sync;
    private final int backfillDays;
    private final int chunkDays;

    public WithingsBackfillService(
        WithingsSyncService sync,
        @Value("${app.withings.backfill-days:1460}") int backfillDays,
        @Value("${app.withings.backfill-chunk-days:365}") int chunkDays
    ) {
        this.sync = sync;
        this.backfillDays = backfillDays;
        this.chunkDays = chunkDays;
    }

    public void scheduleBackfill(String userId) {
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> runBackfill(userId));
    }

    /** Full-history backfill (connect flow). Returns total rows stored. */
    public int runBackfill(String userId) {
        return runBackfill(userId, backfillDays);
    }

    /** Re-pull a bounded recent window (refresh sweep / admin re-sync). */
    public int runBackfill(String userId, int windowDays) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(windowDays));
        log.info("Withings backfill start user={} window=[{},{}]", userId, windowStart, now);
        int total = 0;
        try {
            Instant cursor = windowStart;
            while (cursor.isBefore(now)) {
                Instant chunkEnd = cursor.plus(Duration.ofDays(chunkDays));
                if (chunkEnd.isAfter(now)) chunkEnd = now;
                total += sync.importAll(userId, cursor, chunkEnd);
                cursor = chunkEnd;
            }
            log.info("Withings backfill complete user={} totalStored={}", userId, total);
        } catch (RuntimeException e) {
            log.error("Withings backfill failed user={} totalStored={} cause={}",
                userId, total, e.getMessage(), e);
        }
        return total;
    }
}
