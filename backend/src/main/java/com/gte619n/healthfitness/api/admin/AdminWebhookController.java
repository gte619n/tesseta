package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import com.gte619n.healthfitness.core.platform.WebhookSubscription;
import com.gte619n.healthfitness.core.platform.WebhookSubscriptionStore;
import com.gte619n.healthfitness.platform.AppPlatformProperties;
import com.gte619n.healthfitness.platform.webhook.WebhookEventType;
import com.gte619n.healthfitness.platform.webhook.WebhookSecrets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Admin management of a client's webhook subscription (ADR-0020, D21). One
// subscription per client; the HMAC signing secret is returned ONCE on create
// (KMS-encrypted at rest, decrypted only to sign deliveries).
//
//   POST   /api/admin/oauth-clients/{clientId}/webhook   set url + events
//   GET    /api/admin/oauth-clients/{clientId}/webhook   view (no secret)
//   DELETE /api/admin/oauth-clients/{clientId}/webhook   remove
@RestController
@RequestMapping("/api/admin/oauth-clients/{clientId}/webhook")
@AdminOnly
@ConditionalOnProperty(name = "app.platform.webhooks-enabled", havingValue = "true")
public class AdminWebhookController {

    private final OAuthClientStore clients;
    private final WebhookSubscriptionStore subscriptions;
    private final AppPlatformProperties props;

    public AdminWebhookController(
        OAuthClientStore clients,
        WebhookSubscriptionStore subscriptions,
        AppPlatformProperties props
    ) {
        this.clients = clients;
        this.subscriptions = subscriptions;
        this.props = props;
    }

    @PostMapping
    public CreateResponse create(@PathVariable String clientId, @RequestBody CreateRequest body) {
        clients.findById(clientId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown client"));
        if (body == null || body.url() == null || body.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is required");
        }
        Set<String> events = new LinkedHashSet<>(
            body.eventTypes() == null ? List.of() : body.eventTypes());
        if (events.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "at least one event type is required");
        }
        try {
            WebhookEventType.validateAll(events);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (props.getWebhookSigningKey() == null || props.getWebhookSigningKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "webhook signing key is not configured");
        }
        subscriptions.save(new WebhookSubscription(
            clientId, body.url(), events, true, Instant.now(), Instant.now()));

        // Derived, not stored; shown once here so the developer can verify signatures.
        String secret = WebhookSecrets.deriveSecret(props.getWebhookSigningKey(), clientId);
        return new CreateResponse(clientId, body.url(), events, secret);
    }

    @GetMapping
    public SubscriptionView get(@PathVariable String clientId) {
        WebhookSubscription s = subscriptions.findByClientId(clientId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "no webhook subscription"));
        return new SubscriptionView(s.clientId(), s.url(), s.eventTypes(), s.active(), s.createdAt());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@PathVariable String clientId) {
        subscriptions.delete(clientId);
        return ResponseEntity.noContent().build();
    }

    public record CreateRequest(String url, List<String> eventTypes) {}

    // signingSecret is returned ONLY here, once.
    public record CreateResponse(
        String clientId, String url, Set<String> eventTypes, String signingSecret) {}

    public record SubscriptionView(
        String clientId, String url, Set<String> eventTypes, boolean active, Instant createdAt) {}
}
