package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.DailyMetricDataType;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Handles a single daily-metric webhook notification (steps, resting HR,
// HRV, sleep). Parallels WebhookHandlerService for body composition.
//
// UPSERT hydrates the interval via REST and merges each day's metric into
// the dailyMetrics document. DELETE is logged but not propagated: daily
// aggregates are corrected by re-sending UPSERTs in practice, and a delete
// has no unambiguous "unset which field" semantics on a merged day record.
@Service
public class DailyMetricWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(DailyMetricWebhookHandler.class);

    private final UserRepository users;
    private final DailyMetricRepository dailyMetrics;
    private final DeviceSyncRepository deviceSyncs;
    private final AccessTokenService tokens;
    private final GoogleHealthClient googleHealth;
    private final MetricChangedPublisher metricChangedPublisher;

    public DailyMetricWebhookHandler(
        UserRepository users,
        DailyMetricRepository dailyMetrics,
        DeviceSyncRepository deviceSyncs,
        AccessTokenService tokens,
        GoogleHealthClient googleHealth,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.users = users;
        this.dailyMetrics = dailyMetrics;
        this.deviceSyncs = deviceSyncs;
        this.tokens = tokens;
        this.googleHealth = googleHealth;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    public void handle(Notification notification) {
        Optional<User> match = users.findByHealthUserId(notification.healthUserId);
        if (match.isEmpty()) {
            log.warn("Daily-metric webhook for unknown healthUserId={}", notification.healthUserId);
            return;
        }
        String userId = match.get().userId();
        switch (notification.operation) {
            case UPSERT -> handleUpsert(userId, notification);
            case DELETE -> log.info("Daily-metric DELETE ignored user={} type={} range=[{},{}]",
                userId, notification.dataType, notification.intervalStart, notification.intervalEnd);
        }
    }

    private void handleUpsert(String userId, Notification n) {
        String accessToken = tokens.accessTokenFor(userId);
        List<DailyMetricDataPoint> points = googleHealth.listDailyMetricPoints(
            accessToken, n.dataType, n.intervalStart, n.intervalEnd);

        Set<String> platforms = new LinkedHashSet<>();
        for (DailyMetricDataPoint dp : points) {
            dailyMetrics.save(DailyMetricBackfillService.toDailyMetric(userId, dp));
            if (dp.sourcePlatform() != null && !dp.sourcePlatform().isBlank()) {
                platforms.add(dp.sourcePlatform());
            }
        }
        Instant now = Instant.now();
        for (String platform : platforms) {
            deviceSyncs.recordSync(userId, platform, now);
        }
        log.info("Daily-metric UPSERT user={} type={} stored={}", userId, n.dataType, points.size());

        MetricKey key = metricKeyFor(n.dataType);
        if (key != null && !points.isEmpty()) {
            metricChangedPublisher.publish(userId, key);
        }
    }

    // Steps has no Goals metric key; the three vitals do.
    private static MetricKey metricKeyFor(DailyMetricDataType type) {
        return switch (type) {
            case RESTING_HEART_RATE -> MetricKey.VITALS_RESTING_HR;
            case HRV -> MetricKey.VITALS_HRV;
            case SLEEP -> MetricKey.VITALS_SLEEP_SCORE;
            case STEPS -> null;
        };
    }

    public enum Operation { UPSERT, DELETE }

    public record Notification(
        String healthUserId,
        DailyMetricDataType dataType,
        Operation operation,
        Instant intervalStart,
        Instant intervalEnd
    ) {}
}
