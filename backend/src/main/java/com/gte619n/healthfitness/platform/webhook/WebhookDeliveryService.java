package com.gte619n.healthfitness.platform.webhook;

import com.gte619n.healthfitness.platform.AppPlatformProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Delivers a signed webhook batch over HTTPS (ADR-0020, D20). Retries a few
// times with linear backoff within the call; if all attempts fail the poller
// simply doesn't advance the checkpoint, so the batch is retried on the next
// cycle (at-least-once). Lightweight java.net.http client, mirroring the
// GoogleHealthOAuthClient style (no extra HTTP dependency).
@Component
@ConditionalOnProperty(name = "app.platform.webhooks-enabled", havingValue = "true")
public class WebhookDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final int maxAttempts;

    public WebhookDeliveryService(AppPlatformProperties props) {
        this.maxAttempts = Math.max(1, props.getWebhookMaxAttempts());
    }

    // Returns true iff the endpoint acknowledged with a 2xx. `secret` is the
    // decrypted per-subscription HMAC signing secret.
    public boolean deliver(String url, String secret, String body) {
        if (!isAllowedTarget(url)) {
            log.warn("refusing webhook delivery to disallowed URL: {}", url);
            return false;
        }
        String signature = WebhookSigner.signatureHeader(body, secret);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header(WebhookSigner.HEADER, signature)
                    .header("User-Agent", "Tesseta-Webhooks/1")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
                HttpResponse<Void> res = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() / 100 == 2) {
                    return true;
                }
                log.warn("webhook delivery to {} returned {} (attempt {}/{})",
                    url, res.statusCode(), attempt, maxAttempts);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.warn("webhook delivery to {} failed (attempt {}/{}): {}",
                    url, attempt, maxAttempts, e.toString());
            }
            sleep(attempt);
        }
        return false;
    }

    // Admin-configured URLs, so this is defense-in-depth, not the primary control:
    // require an absolute http(s) URL and reject obvious loopback/link-local hosts.
    private static boolean isAllowedTarget(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return false;
            if (!scheme.equals("https") && !scheme.equals("http")) return false;
            String h = host.toLowerCase();
            return !(h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")
                || h.startsWith("169.254.") || h.startsWith("10.")
                || h.startsWith("192.168.") || h.equals("metadata.google.internal"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(Math.min(5000L, 500L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
