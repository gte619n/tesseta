package com.gte619n.healthfitness.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public class AppAuthProperties {
    private List<String> allowedAudiences = List.of();
    private boolean devMode = false;
    private boolean devLoginEnabled = false;

    public List<String> getAllowedAudiences() {
        return allowedAudiences;
    }

    public void setAllowedAudiences(List<String> allowedAudiences) {
        this.allowedAudiences = allowedAudiences;
    }

    public boolean isDevMode() {
        return devMode;
    }

    public void setDevMode(boolean devMode) {
        this.devMode = devMode;
    }

    // UAT / local end-to-end only: enables POST /api/auth/dev-login, which mints
    // a real backend session token for an arbitrary identity (no Google sign-in).
    // Independent of dev-mode on purpose: UAT keeps dev-mode OFF so the resource
    // server validates the minted session token through the real sessionDecoder
    // path — exactly as Android does in production — rather than the X-Dev-User
    // bypass. Defaults false; never set true in production.
    public boolean isDevLoginEnabled() {
        return devLoginEnabled;
    }

    public void setDevLoginEnabled(boolean devLoginEnabled) {
        this.devLoginEnabled = devLoginEnabled;
    }
}
