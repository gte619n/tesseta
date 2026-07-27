package com.gte619n.healthfitness.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Configuration for the third-party OAuth platform API (ADR-0020). Distinct
// from the first-party session tokens (app.session, ADR-0010): a separate token
// family with its own issuer and audience, signed RS256 so external integrators
// can validate offline and we can rotate the key.
//
//   enabled     — master switch. When false, PlatformKeys and every /oauth and
//                 /v1 bean stays dormant (tests turn it off). Default true; with
//                 no clients registered nothing is reachable anyway.
//   issuer      — the `iss` claim on platform access tokens. Distinct from
//                 accounts.google.com and tesseta-backend so the resource server
//                 routes by issuer to the RS256 decoder.
//   audience    — the `aud` claim; the resource server requires it, so a
//                 first-party or Google token can never satisfy a /v1 route.
//   rsa-private-key / key-id — PEM (PKCS#8) signing key + its JWKS `kid`. When
//                 the PEM is blank, PlatformKeys generates an ephemeral keypair
//                 at startup (fine for local/dev; a restart invalidates live
//                 access tokens — refresh tokens survive). Supply from Secret
//                 Manager in deployed environments for stable keys.
//   access-ttl  — access-token lifetime (short by design; refresh to renew).
//   refresh-ttl — refresh-token lifetime (issued only with offline_access).
//   code-ttl    — authorization-code lifetime (single-use, seconds-scale).
@ConfigurationProperties(prefix = "app.platform")
public class AppPlatformProperties {
    private boolean enabled = true;
    private String issuer = "tesseta-platform";
    private String audience = "tesseta-platform-api";
    private String rsaPrivateKey = "";
    private String keyId = "";
    // Fail-closed guard (decision D22): when false, PlatformKeys refuses to start
    // without a configured rsa-private-key instead of generating an ephemeral one.
    // Code default true so unit tests generate a key; application.yml sets it from
    // ${PLATFORM_ALLOW_EPHEMERAL_KEY:false} so deployed instances fail fast.
    private boolean allowEphemeralKey = true;
    // The browser-navigable consent page (hosted by the web app) advertised as
    // the `authorization_endpoint` in discovery. It calls the backend
    // GET /oauth/authorize + POST /oauth/authorize/consent APIs. When blank,
    // discovery advertises the backend path directly (fine for API testing).
    private String consentPageUrl = "";
    private Duration accessTtl = Duration.ofMinutes(15);
    private Duration refreshTtl = Duration.ofDays(60);
    private Duration codeTtl = Duration.ofMinutes(5);
    // Per (client, user) fixed-window rate limit on the /v1 API.
    private int rateLimitRequests = 600;
    private Duration rateLimitWindow = Duration.ofMinutes(5);
    // Outbound webhooks (ADR-0020, D15). Off by default; when on, the poller
    // pushes signed event batches to admin-configured subscription URLs.
    private boolean webhooksEnabled = false;
    private int webhookMaxAttempts = 5;
    // Master key from which each client's webhook HMAC signing secret is derived
    // (never stored). Required when webhooks are enabled.
    private String webhookSigningKey = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public String getRsaPrivateKey() {
        return rsaPrivateKey;
    }

    public void setRsaPrivateKey(String rsaPrivateKey) {
        this.rsaPrivateKey = rsaPrivateKey;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public boolean isAllowEphemeralKey() {
        return allowEphemeralKey;
    }

    public void setAllowEphemeralKey(boolean allowEphemeralKey) {
        this.allowEphemeralKey = allowEphemeralKey;
    }

    public String getConsentPageUrl() {
        return consentPageUrl;
    }

    public void setConsentPageUrl(String consentPageUrl) {
        this.consentPageUrl = consentPageUrl;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }

    public void setAccessTtl(Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
        this.refreshTtl = refreshTtl;
    }

    public Duration getCodeTtl() {
        return codeTtl;
    }

    public void setCodeTtl(Duration codeTtl) {
        this.codeTtl = codeTtl;
    }

    public int getRateLimitRequests() {
        return rateLimitRequests;
    }

    public void setRateLimitRequests(int rateLimitRequests) {
        this.rateLimitRequests = rateLimitRequests;
    }

    public Duration getRateLimitWindow() {
        return rateLimitWindow;
    }

    public void setRateLimitWindow(Duration rateLimitWindow) {
        this.rateLimitWindow = rateLimitWindow;
    }

    public boolean isWebhooksEnabled() {
        return webhooksEnabled;
    }

    public void setWebhooksEnabled(boolean webhooksEnabled) {
        this.webhooksEnabled = webhooksEnabled;
    }

    public int getWebhookMaxAttempts() {
        return webhookMaxAttempts;
    }

    public void setWebhookMaxAttempts(int webhookMaxAttempts) {
        this.webhookMaxAttempts = webhookMaxAttempts;
    }

    public String getWebhookSigningKey() {
        return webhookSigningKey;
    }

    public void setWebhookSigningKey(String webhookSigningKey) {
        this.webhookSigningKey = webhookSigningKey;
    }
}
