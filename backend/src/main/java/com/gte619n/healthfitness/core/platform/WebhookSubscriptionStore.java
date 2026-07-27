package com.gte619n.healthfitness.core.platform;

import java.util.List;
import java.util.Optional;

// Persistence port for webhook subscriptions (ADR-0020). One per client.
public interface WebhookSubscriptionStore {

    void save(WebhookSubscription subscription);

    Optional<WebhookSubscription> findByClientId(String clientId);

    List<WebhookSubscription> findAllActive();

    void delete(String clientId);
}
