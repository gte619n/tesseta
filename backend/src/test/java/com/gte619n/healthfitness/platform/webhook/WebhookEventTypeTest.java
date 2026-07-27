package com.gte619n.healthfitness.platform.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WebhookEventTypeTest {

    @Test
    void eligibleRequiresBothSubscriptionAndScope() {
        // Subscribed to dose.logged but no medications:read scope -> not eligible.
        assertThat(WebhookEventType.eligible(Set.of("dose.logged"), Set.of("workouts:read")))
            .isEmpty();
        // Has the scope but didn't subscribe -> not eligible.
        assertThat(WebhookEventType.eligible(Set.of("workout.completed"), Set.of("medications:read")))
            .isEmpty();
        // Both present -> eligible.
        assertThat(WebhookEventType.eligible(Set.of("dose.logged"), Set.of("medications:read")))
            .containsExactly(WebhookEventType.DOSE_LOGGED);
    }

    @Test
    void validateAllRejectsUnknownEventTypes() {
        assertThatThrownBy(() -> WebhookEventType.validateAll(Set.of("dose.logged", "not.real")))
            .isInstanceOf(IllegalArgumentException.class);
        WebhookEventType.validateAll(Set.of("dose.logged", "workout.completed")); // no throw
    }

    @Test
    void labsScopeCoversThreeEventTypes() {
        assertThat(WebhookEventType.eligible(WebhookEventType.allWire(), Set.of("labs:read")))
            .containsExactlyInAnyOrder(
                WebhookEventType.LAB_ADDED,
                WebhookEventType.DEXA_ADDED,
                WebhookEventType.DAILY_METRIC_UPDATED);
    }
}
