package com.gte619n.healthfitness.api.platform;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import com.gte619n.healthfitness.core.platform.OAuthGrant;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import com.gte619n.healthfitness.core.platform.PlatformRefreshTokenStore;
import com.gte619n.healthfitness.platform.PlatformScope;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// First-party "Connected Apps" management (ADR-0020). The account owner lists
// every third-party app they've authorized and can disconnect one, which
// deletes the standing grant AND burns that app's refresh tokens so its
// background access stops immediately (outstanding ~15-min access tokens simply
// expire). Lives under /api/me so it's covered by the existing first-party auth.
@RestController
@RequestMapping("/api/me/connected-apps")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectedAppsController {

    private final CurrentUserProvider currentUser;
    private final OAuthGrantStore grants;
    private final OAuthClientStore clients;
    private final PlatformRefreshTokenStore refreshTokens;

    public ConnectedAppsController(
        CurrentUserProvider currentUser,
        OAuthGrantStore grants,
        OAuthClientStore clients,
        PlatformRefreshTokenStore refreshTokens
    ) {
        this.currentUser = currentUser;
        this.grants = grants;
        this.clients = clients;
        this.refreshTokens = refreshTokens;
    }

    @GetMapping
    public List<ConnectedApp> list() {
        String userId = currentUser.get().userId();
        return grants.findByUser(userId).stream()
            .map(this::toConnectedApp)
            .toList();
    }

    @DeleteMapping("/{clientId}")
    public ResponseEntity<Void> disconnect(@PathVariable String clientId) {
        String userId = currentUser.get().userId();
        grants.delete(userId, clientId);
        refreshTokens.revokeForUserAndClient(userId, clientId);
        return ResponseEntity.noContent().build();
    }

    private ConnectedApp toConnectedApp(OAuthGrant grant) {
        OAuthClient client = clients.findById(grant.clientId()).orElse(null);
        List<ScopeInfo> scopeInfos = grant.scopes().stream()
            .map(s -> new ScopeInfo(s, PlatformScope.fromWire(s)
                .map(PlatformScope::consentDescription).orElse(s)))
            .toList();
        return new ConnectedApp(
            grant.clientId(),
            client == null ? grant.clientId() : client.name(),
            client == null ? null : client.logoUrl(),
            scopeInfos,
            grant.grantedAt());
    }

    public record ConnectedApp(
        String clientId,
        String name,
        String logoUrl,
        List<ScopeInfo> scopes,
        Instant grantedAt
    ) {}

    public record ScopeInfo(String scope, String description) {}
}
