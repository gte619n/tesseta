package com.gte619n.healthfitness.platform.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Enables @Scheduled only when webhooks are on (ADR-0020), so the WebhookPoller
// tick is dormant everywhere the feature flag is off (default) — no scheduler
// thread, no polling.
@Configuration
@ConditionalOnProperty(name = "app.platform.webhooks-enabled", havingValue = "true")
@EnableScheduling
public class WebhookSchedulingConfig {}
