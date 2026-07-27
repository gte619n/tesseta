package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import com.gte619n.healthfitness.platform.PlatformCrypto;
import com.gte619n.healthfitness.platform.PlatformScope;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

// Admin-only registration of third-party OAuth clients (ADR-0020). Phase-1
// onboarding is admin-gated (a self-serve developer portal is deferred), reusing
// the same @AdminOnly method security as the catalog admin endpoints.
//
//   POST /api/admin/oauth-clients   register; returns the client_secret ONCE for
//                                   confidential clients (never retrievable again)
//   GET  /api/admin/oauth-clients   list (never includes secrets)
@RestController
@RequestMapping("/api/admin/oauth-clients")
@AdminOnly
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class AdminOAuthClientController {

    private final OAuthClientStore clients;

    public AdminOAuthClientController(OAuthClientStore clients) {
        this.clients = clients;
    }

    @PostMapping
    public RegisterResponse register(@RequestBody RegisterRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (body.redirectUris() == null || body.redirectUris().isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "at least one redirect_uri is required");
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String scope : body.scopes() == null ? List.<String>of() : body.scopes()) {
            if (!PlatformScope.isKnown(scope)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "unknown scope: " + scope);
            }
            scopes.add(scope);
        }
        if (scopes.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "at least one scope is required");
        }

        String clientId = "cli_" + UUID.randomUUID().toString().replace("-", "");
        // Confidential clients get a secret; public (PKCE-only) clients do not.
        String secret = body.confidential() ? PlatformCrypto.randomToken() : null;
        String secretHash = secret == null ? null : PlatformCrypto.sha256(secret);

        clients.save(new OAuthClient(
            clientId, body.name(), body.logoUrl(),
            List.copyOf(body.redirectUris()), scopes, secretHash, Instant.now()));

        return new RegisterResponse(
            clientId, secret, body.name(), body.logoUrl(),
            List.copyOf(body.redirectUris()), scopes, body.confidential());
    }

    @GetMapping
    public List<ClientSummary> list() {
        return clients.findAll().stream()
            .map(c -> new ClientSummary(
                c.clientId(), c.name(), c.logoUrl(), c.redirectUris(),
                c.allowedScopes(), c.isConfidential()))
            .toList();
    }

    public record RegisterRequest(
        String name,
        String logoUrl,
        List<String> redirectUris,
        List<String> scopes,
        boolean confidential
    ) {}

    // client_secret is populated ONLY here, once, for confidential clients.
    public record RegisterResponse(
        String clientId,
        String clientSecret,
        String name,
        String logoUrl,
        List<String> redirectUris,
        Set<String> scopes,
        boolean confidential
    ) {}

    public record ClientSummary(
        String clientId,
        String name,
        String logoUrl,
        List<String> redirectUris,
        Set<String> scopes,
        boolean confidential
    ) {}
}
