package com.gte619n.healthfitness.googlehealth;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.device.DeviceSyncRepository;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthClient;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataPoint;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthDataType;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

// Handles a single webhook notification once the controller has parsed
// it. Idempotent on UPSERT (record IDs are stable Firestore doc keys);
// DELETE removes everything in the notification's interval for the
// reported data type.
@Service
public class WebhookHandlerService {

    private static final Logger log = LoggerFactory.getLogger(WebhookHandlerService.class);

    private final UserRepository users;
    private final BodyCompositionRepository measurements;
    private final DeviceSyncRepository deviceSyncs;
    private final AccessTokenService tokens;
    private final GoogleHealthClient googleHealth;
    private final MetricChangedPublisher metricChangedPublisher;

    public WebhookHandlerService(
        UserRepository users,
        BodyCompositionRepository measurements,
        DeviceSyncRepository deviceSyncs,
        AccessTokenService tokens,
        GoogleHealthClient googleHealth,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.users = users;
        this.measurements = measurements;
        this.deviceSyncs = deviceSyncs;
        this.tokens = tokens;
        this.googleHealth = googleHealth;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    public void handle(Notification notification) {
        Optional<User> match = users.findByHealthUserId(notification.healthUserId);
        if (match.isEmpty()) {
            // Notification arrived before we had a chance to record the
            // healthUserId. Drop on the floor; Google will retry for 7
            // days and the connect endpoint backfills regardless.
            log.warn("Webhook notification for unknown healthUserId={}",
                notification.healthUserId);
            return;
        }
        String userId = match.get().userId();
        switch (notification.operation) {
            case UPSERT -> handleUpsert(userId, notification);
            case DELETE -> handleDelete(userId, notification);
        }
    }

    private void handleUpsert(String userId, Notification n) {
        String accessToken = tokens.accessTokenFor(userId);
        List<GoogleHealthDataPoint> points = googleHealth.listDataPoints(
            accessToken, n.dataType, n.intervalStart, n.intervalEnd);
        List<BodyCompositionMeasurement> measurementsList = points.stream()
            .map(dp -> toMeasurement(userId, dp))
            .toList();
        measurements.saveAll(measurementsList);
        log.info("Webhook UPSERT user={} type={} stored={}",
            userId, n.dataType, measurementsList.size());
        // Record a device sync for each distinct source platform we just
        // ingested data from, so the clients can show device freshness.
        Set<String> platforms = new LinkedHashSet<>();
        for (BodyCompositionMeasurement m : measurementsList) {
            if (m.sourcePlatform() != null && !m.sourcePlatform().isBlank()) {
                platforms.add(m.sourcePlatform());
            }
        }
        Instant now = Instant.now();
        for (String platform : platforms) {
            deviceSyncs.recordSync(userId, platform, now);
        }
        // Publish after save; collect distinct metric keys from the saved measurements.
        Set<MetricKey> keys = new LinkedHashSet<>();
        for (BodyCompositionMeasurement m : measurementsList) {
            MetricKey key = MetricKey.fromBodyCompositionMetric(m.metric());
            if (key != null) keys.add(key);
        }
        metricChangedPublisher.publishAll(userId, keys);
    }

    private void handleDelete(String userId, Notification n) {
        measurements.deleteByUserMetricAndRange(
            userId, n.dataType.toMetric(), n.intervalStart, n.intervalEnd);
        log.info("Webhook DELETE user={} type={} range=[{},{}]",
            userId, n.dataType, n.intervalStart, n.intervalEnd);
        // Publish after delete — the metric value may have changed if rows were removed.
        MetricKey key = MetricKey.fromBodyCompositionMetric(n.dataType.toMetric());
        if (key != null) {
            metricChangedPublisher.publish(userId, key);
        }
    }

    private static BodyCompositionMeasurement toMeasurement(String userId, GoogleHealthDataPoint dp) {
        return new BodyCompositionMeasurement(
            userId,
            dp.recordId(),
            dp.dataType().toMetric(),
            dp.value(),
            dp.sampleTime(),
            dp.sourcePlatform(),
            dp.recordingMethod(),
            null,
            null
        );
    }

    public enum Operation { UPSERT, DELETE }

    public record Notification(
        String healthUserId,
        GoogleHealthDataType dataType,
        Operation operation,
        Instant intervalStart,
        Instant intervalEnd
    ) {}
}
