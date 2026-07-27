package com.gte619n.healthfitness.platform.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.platform.OAuthGrant;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import com.gte619n.healthfitness.core.platform.WebhookCheckpointStore;
import com.gte619n.healthfitness.core.platform.WebhookSubscription;
import com.gte619n.healthfitness.core.platform.WebhookSubscriptionStore;
import com.gte619n.healthfitness.platform.AppPlatformProperties;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// The webhook outbox poller (ADR-0020, D19). On each tick it walks every active
// subscription, and for every user who granted that client, collects the events
// that occurred since the (client,user) checkpoint and delivers them as one
// signed batch. The checkpoint advances only on a successful (or empty)
// delivery, so a failed POST is retried next tick (at-least-once) without gaps.
//
// This reuses the /v1 read repositories instead of hooking domain write paths,
// so it adds no coupling or risk to existing features; the trade-off is
// poll-interval latency, which is fine for monitoring.
@Component
@ConditionalOnProperty(name = "app.platform.webhooks-enabled", havingValue = "true")
public class WebhookPoller {

    private static final Logger log = LoggerFactory.getLogger(WebhookPoller.class);

    private final WebhookSubscriptionStore subscriptions;
    private final OAuthGrantStore grants;
    private final WebhookCheckpointStore checkpoints;
    private final WebhookEventCollector collector;
    private final WebhookDeliveryService delivery;
    private final AppPlatformProperties props;
    private final ObjectMapper objectMapper;

    public WebhookPoller(
        WebhookSubscriptionStore subscriptions,
        OAuthGrantStore grants,
        WebhookCheckpointStore checkpoints,
        WebhookEventCollector collector,
        WebhookDeliveryService delivery,
        AppPlatformProperties props,
        ObjectMapper objectMapper
    ) {
        this.subscriptions = subscriptions;
        this.grants = grants;
        this.checkpoints = checkpoints;
        this.collector = collector;
        this.delivery = delivery;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.platform.webhook-poll-ms:60000}")
    public void poll() {
        Instant until = Instant.now();
        for (WebhookSubscription sub : subscriptions.findAllActive()) {
            deliverForSubscription(sub, until);
        }
    }

    private void deliverForSubscription(WebhookSubscription sub, Instant until) {
        String secret = WebhookSecrets.deriveSecret(props.getWebhookSigningKey(), sub.clientId());
        for (OAuthGrant grant : grants.findByClient(sub.clientId())) {
            String userId = grant.userId();
            // No checkpoint yet => start from now (no historical backfill).
            Instant since = checkpoints.find(sub.clientId(), userId).orElse(until);
            try {
                List<WebhookEvent> events =
                    collector.collect(userId, grant.scopes(), sub.eventTypes(), since, until);
                if (events.isEmpty()) {
                    checkpoints.save(sub.clientId(), userId, until);
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("clientId", sub.clientId());
                body.put("deliveredAt", until.toString());
                body.put("events", events);
                if (delivery.deliver(sub.url(), secret, objectMapper.writeValueAsString(body))) {
                    checkpoints.save(sub.clientId(), userId, until);
                } else {
                    log.warn("webhook batch to client {} user {} failed; retrying next tick",
                        sub.clientId(), userId);
                }
            } catch (Exception e) {
                log.warn("error building/delivering webhook for client {} user {}",
                    sub.clientId(), userId, e);
            }
        }
    }
}
