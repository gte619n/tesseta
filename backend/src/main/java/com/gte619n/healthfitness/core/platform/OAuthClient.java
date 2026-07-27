package com.gte619n.healthfitness.core.platform;

import java.time.Instant;
import java.util.List;
import java.util.Set;

// A registered third-party application (ADR-0020). Stored in `oauthClients`.
//
// PKCE is mandatory for every client, so a `secretHash` is optional: a public
// client (mobile / SPA that cannot keep a secret) has none and is protected by
// PKCE alone; a confidential client (a server backend) additionally presents a
// client secret at the token endpoint. Like refresh tokens, the secret is only
// ever stored as a hash — it is shown to the developer once at registration and
// never recoverable, which is stronger than storing it (even encrypted) for
// later display.
public record OAuthClient(
    String clientId,
    String name,
    String logoUrl,
    List<String> redirectUris,
    Set<String> allowedScopes,
    String secretHash, // null/blank => public (PKCE-only) client
    Instant createdAt
) {
    public boolean isConfidential() {
        return secretHash != null && !secretHash.isBlank();
    }

    // Redirect URIs are matched exactly (no prefix / wildcard) — the standard
    // defense against open-redirect and code-interception via a look-alike host.
    public boolean allowsRedirectUri(String uri) {
        return uri != null && redirectUris.contains(uri);
    }

    public boolean allowsScope(String scope) {
        return allowedScopes.contains(scope);
    }
}
