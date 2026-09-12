package com.gte619n.healthfitness.core.nutrition;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around Spring's {@link ApplicationEventPublisher} so
 * {@link LeftoverService} publishes a {@link LeftoverReviewReadyEvent} without
 * importing the publisher directly — mirroring {@code SyncChangeNotifier}
 * (IMPL-LEFTOVER-01, IL-6).
 *
 * <p><b>Publish AFTER the persistent write.</b> The analysis result is saved
 * before the event fires, so a missed push only means the user relies on the
 * in-app pending-review state (which syncs regardless).
 */
@Component
public class LeftoverReviewPublisher {

    private final ApplicationEventPublisher publisher;

    public LeftoverReviewPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void reviewReady(LeftoverReviewReadyEvent event) {
        if (event == null) {
            return;
        }
        publisher.publishEvent(event);
    }
}
