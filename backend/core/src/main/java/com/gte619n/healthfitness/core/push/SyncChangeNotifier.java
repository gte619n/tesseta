package com.gte619n.healthfitness.core.push;

import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring's {@link ApplicationEventPublisher} so writer
 * modules ({@code api} controllers, {@code app} webhook handlers) publish a
 * {@link SyncChangedEvent} without importing {@code ApplicationEventPublisher}
 * directly — mirroring {@code MetricChangedPublisher} (IMPL-AND-20, Phase 2).
 *
 * <p><b>Publish AFTER the persistent write, never before.</b> If the write
 * throws, no fan-out event should be in flight.
 */
@Component
public class SyncChangeNotifier {

    private final ApplicationEventPublisher publisher;

    public SyncChangeNotifier(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Announce that the given collections changed for the user.
     *
     * @param userId         owning user.
     * @param originDeviceId the device that produced the change (suppressed from
     *                       fan-out, D18); {@code null} for server-originated
     *                       changes that should reach every device.
     * @param collections    the changed collection names.
     */
    public void changed(String userId, String originDeviceId, String... collections) {
        if (userId == null || collections.length == 0) {
            return;
        }
        publisher.publishEvent(
            new SyncChangedEvent(userId, List.of(collections), originDeviceId));
    }
}
