package com.gte619n.healthfitness.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Configuration for the backend-issued session tokens that native clients
// (Android phone + Wear) use as their API bearer. See ADR-0010.
//
//   signing-key — HS256 secret (>= 32 bytes). Supplied from Secret Manager at
//                 deploy time; never committed. Empty disables issuance, in
//                 which case /api/auth/exchange returns 503.
//   issuer      — the `iss` claim stamped on (and required of) our access
//                 tokens. Distinct from accounts.google.com so the resource
//                 server can route a token to the right decoder by issuer.
//   access-ttl  — access-token lifetime (default 1h, matching Google's).
//   refresh-ttl — refresh-token lifetime (default 60d, sliding via rotation).
//   reuse-grace — how long after rotation a just-rotated refresh token may be
//                 replayed and still be honoured (re-issued) instead of treated
//                 as theft. Absorbs benign client retries of a refresh whose
//                 response was lost in flight on a flaky mobile network, which
//                 would otherwise burn the whole session family and force an
//                 interactive re-login. Default 30s.
@ConfigurationProperties(prefix = "app.session")
public class AppSessionProperties {
    private String signingKey = "";
    private String issuer = "tesseta-backend";
    private Duration accessTtl = Duration.ofHours(1);
    private Duration refreshTtl = Duration.ofDays(60);
    private Duration reuseGrace = Duration.ofSeconds(30);

    public String getSigningKey() {
        return signingKey;
    }

    public void setSigningKey(String signingKey) {
        this.signingKey = signingKey;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
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

    public Duration getReuseGrace() {
        return reuseGrace;
    }

    public void setReuseGrace(Duration reuseGrace) {
        this.reuseGrace = reuseGrace;
    }

    public boolean isEnabled() {
        return signingKey != null && !signingKey.isBlank();
    }
}
