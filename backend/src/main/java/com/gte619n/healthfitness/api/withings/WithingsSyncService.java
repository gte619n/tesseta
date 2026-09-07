package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.withings.WithingsApiClient;
import com.gte619n.healthfitness.integrations.withings.WithingsBodyPoint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Pull-and-persist core for Withings, shared by the connect-time backfill, the
// periodic refresh sweep, and the webhook handler. Fetches a family (sleep or
// body) for a [from, to] window, writes it into the same provider-agnostic
// DailyMetric / BodyComposition stores the Google Health path uses, records a
// WITHINGS device sync, and fans out sync + metric-changed events.
@Service
public class WithingsSyncService {

    private static final Logger log = LoggerFactory.getLogger(WithingsSyncService.class);

    private final WithingsAccessTokenService tokens;
    private final WithingsApiClient api;
    private final DailyMetricRepository dailyMetrics;
    private final BodyCompositionRepository measurements;
    private final DeviceSyncRepository deviceSyncs;
    private final SyncChangeNotifier syncNotifier;
    private final MetricChangedPublisher metricChangedPublisher;

    public WithingsSyncService(
        WithingsAccessTokenService tokens,
        WithingsApiClient api,
        DailyMetricRepository dailyMetrics,
        BodyCompositionRepository measurements,
        DeviceSyncRepository deviceSyncs,
        SyncChangeNotifier syncNotifier,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.tokens = tokens;
        this.api = api;
        this.dailyMetrics = dailyMetrics;
        this.measurements = measurements;
        this.deviceSyncs = deviceSyncs;
        this.syncNotifier = syncNotifier;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    /** Pull both families for the window. Returns the total rows stored. */
    public int importAll(String userId, Instant from, Instant to) {
        return importSleep(userId, from, to) + importBody(userId, from, to);
    }

    /** Pull sleep summaries (total minutes, score, resting-HR proxy). */
    public int importSleep(String userId, Instant from, Instant to) {
        String accessToken = tokens.accessTokenFor(userId);
        List<DailyMetricDataPoint> points = api.listSleepMetrics(accessToken, from, to);
        Set<MetricKey> keys = new LinkedHashSet<>();
        for (DailyMetricDataPoint dp : points) {
            dailyMetrics.save(toDailyMetric(userId, dp));
            MetricKey key = metricKeyFor(dp.type());
            if (key != null) keys.add(key);
        }
        if (!points.isEmpty()) {
            deviceSyncs.recordSync(userId, WithingsApiClient.PLATFORM, Instant.now());
            syncNotifier.changed(userId, null, "dailyMetrics");
            metricChangedPublisher.publishAll(userId, keys);
        }
        log.info("Withings sleep import user={} stored={}", userId, points.size());
        return points.size();
    }

    /** Pull weight + fat-ratio measurements. */
    public int importBody(String userId, Instant from, Instant to) {
        String accessToken = tokens.accessTokenFor(userId);
        List<WithingsBodyPoint> points = api.listBodyMeasurements(accessToken, from, to);
        List<BodyCompositionMeasurement> rows = new ArrayList<>();
        Set<MetricKey> keys = new LinkedHashSet<>();
        for (WithingsBodyPoint p : points) {
            rows.add(new BodyCompositionMeasurement(
                userId, p.recordId(), p.metric(), p.value(), p.sampleTime(),
                WithingsApiClient.PLATFORM, p.recordingMethod(), null, null));
            MetricKey key = MetricKey.fromBodyCompositionMetric(p.metric());
            if (key != null) keys.add(key);
        }
        measurements.saveAll(rows);
        if (!rows.isEmpty()) {
            deviceSyncs.recordSync(userId, WithingsApiClient.PLATFORM, Instant.now());
            syncNotifier.changed(userId, null, "bodyComposition");
            metricChangedPublisher.publishAll(userId, keys);
        }
        log.info("Withings body import user={} stored={}", userId, rows.size());
        return rows.size();
    }

    // Build a DailyMetric carrying only the field(s) this point maps to; the
    // repository merges it into the day's document. Mirrors
    // DailyMetricBackfillService.toDailyMetric (which is package-private to the
    // googlehealth package) for the two Withings-produced types.
    private static DailyMetric toDailyMetric(String userId, DailyMetricDataPoint dp) {
        Integer restingHr = dp.type() == DailyMetricDataType.RESTING_HEART_RATE ? dp.value() : null;
        Integer sleepMinutes = dp.type() == DailyMetricDataType.SLEEP ? dp.value() : null;
        Integer sleepScore = dp.type() == DailyMetricDataType.SLEEP ? dp.sleepScore() : null;
        return new DailyMetric(
            userId, dp.date(), null, restingHr, sleepMinutes, null, sleepScore, null, null);
    }

    private static MetricKey metricKeyFor(DailyMetricDataType type) {
        return switch (type) {
            case RESTING_HEART_RATE -> MetricKey.VITALS_RESTING_HR;
            case SLEEP -> MetricKey.VITALS_SLEEP_SCORE;
            default -> null;
        };
    }
}
