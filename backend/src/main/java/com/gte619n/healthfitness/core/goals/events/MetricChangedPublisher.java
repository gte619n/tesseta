package com.gte619n.healthfitness.core.goals.events;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import java.time.Instant;
import java.util.Collection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring's {@link ApplicationEventPublisher} so
 * writer modules don't import ApplicationEventPublisher directly and
 * so the event construction is in one place.
 *
 * <p>Publish AFTER the save, never before. If {@code save()} throws,
 * no event should be in flight.
 */
@Component
public class MetricChangedPublisher {

    private final ApplicationEventPublisher publisher;

    public MetricChangedPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Publish a single metric-changed event. {@code key} must not be null. */
    public void publish(String userId, MetricKey key) {
        publisher.publishEvent(new MetricChangedEvent(userId, key.key(), Instant.now()));
    }

    /**
     * Publish one event per key in the collection.
     * Null keys (returned by helper methods when a source enum has no
     * MetricKey mapping) should be filtered out before calling this.
     */
    public void publishAll(String userId, Collection<MetricKey> keys) {
        for (MetricKey k : keys) {
            publish(userId, k);
        }
    }
}
