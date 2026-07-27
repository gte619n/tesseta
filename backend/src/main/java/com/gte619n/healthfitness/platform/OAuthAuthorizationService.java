package com.gte619n.healthfitness.platform;

import com.gte619n.healthfitness.core.platform.AuthorizationCode;
import com.gte619n.healthfitness.core.platform.AuthorizationCodeStore;
import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import com.gte619n.healthfitness.core.platform.OAuthGrant;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import com.gte619n.healthfitness.platform.PlatformTokenService.InvalidRefreshTokenException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

// The authorization-server core (ADR-0020): validates an /oauth/authorize
// request, issues a single-use PKCE-bound code once the user consents, and
// redeems that code (or a refresh token) at /oauth/token. Client authentication,
// redirect-URI exact match, scope subsetting, and PKCE all live here so the
// controllers stay thin. Gated on app.platform.enabled alongside the rest of
// the platform beans.
@Service
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthAuthorizationService {

    private final AppPlatformProperties props;
    private final OAuthClientStore clients;
    private final AuthorizationCodeStore codes;
    private final OAuthGrantStore grants;
    private final PlatformTokenService tokens;

    public OAuthAuthorizationService(
        AppPlatformProperties props,
        OAuthClientStore clients,
        AuthorizationCodeStore codes,
        OAuthGrantStore grants,
        PlatformTokenService tokens
    ) {
        this.props = props;
        this.clients = clients;
        this.codes = codes;
        this.grants = grants;
        this.tokens = tokens;
    }

    // A validated /oauth/authorize request, ready to render consent for and (on
    // approval) issue a code against. `scopeDescriptions` is the per-scope
    // human copy the consent screen lists.
    public record AuthorizationRequest(
        OAuthClient client,
        String redirectUri,
        Set<String> scopes,
        String codeChallenge,
        String codeChallengeMethod,
        String state,
        List<ScopeDescription> scopeDescriptions,
        boolean previouslyGranted
    ) {}

    public record ScopeDescription(String scope, String description) {}

    // Validate every /oauth/authorize parameter for the authenticated `userId`.
    // Errors that occur before the redirect_uri is trusted (unknown client, bad
    // redirect) must surface to the caller, never redirect — the standard
    // open-redirect guard.
    public AuthorizationRequest validate(
        String userId, String clientId, String redirectUri, String responseType,
        String scopeParam, String codeChallenge, String codeChallengeMethod, String state
    ) {
        OAuthClient client = clients.findById(clientId)
            .orElseThrow(() -> OAuthException.invalidClient("unknown client"));
        if (!client.allowsRedirectUri(redirectUri)) {
            throw OAuthException.invalidRequest("redirect_uri is not registered for this client");
        }
        if (!"code".equals(responseType)) {
            throw new OAuthException("unsupported_response_type", "response_type must be 'code'");
        }
        Set<String> scopes = PlatformScope.parse(scopeParam)
            .orElseThrow(() -> OAuthException.invalidScope("unknown or empty scope"));
        for (String scope : scopes) {
            if (!client.allowsScope(scope)) {
                throw OAuthException.invalidScope("client is not permitted the scope: " + scope);
            }
        }
        String method = codeChallengeMethod == null || codeChallengeMethod.isBlank()
            ? Pkce.METHOD_S256 : codeChallengeMethod;
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw OAuthException.invalidRequest("code_challenge is required (PKCE)");
        }
        if (!Pkce.isSupportedMethod(method)) {
            throw OAuthException.invalidRequest("unsupported code_challenge_method");
        }

        // A prior grant covering all requested scopes lets the consent screen
        // offer a lighter "reconnect" instead of a fresh authorization.
        boolean previouslyGranted = grants.find(userId, clientId)
            .map(g -> g.scopes().containsAll(scopes))
            .orElse(false);

        List<ScopeDescription> descriptions = scopes.stream()
            .map(s -> new ScopeDescription(s, PlatformScope.fromWire(s)
                .map(PlatformScope::consentDescription).orElse(s)))
            .toList();

        return new AuthorizationRequest(
            client, redirectUri, scopes, codeChallenge, method, state,
            descriptions, previouslyGranted);
    }

    // Consent granted: mint a single-use code bound to the user + client +
    // redirect + scopes + PKCE challenge, and persist the standing grant (union
    // with any scopes the user previously granted this client).
    public String issueCode(AuthorizationRequest req, String userId, String email, String name) {
        String code = PlatformCrypto.randomToken();
        Instant now = Instant.now();
        codes.save(new AuthorizationCode(
            PlatformCrypto.sha256(code),
            req.client().clientId(),
            userId,
            email,
            name,
            req.redirectUri(),
            req.scopes(),
            req.codeChallenge(),
            req.codeChallengeMethod(),
            now,
            now.plus(props.getCodeTtl())
        ));
        persistGrant(userId, req.client().clientId(), req.scopes(), now);
        return code;
    }

    // authorization_code grant at /oauth/token.
    public IssuedTokens exchangeAuthorizationCode(
        String clientId, String clientSecret, String code, String redirectUri, String codeVerifier
    ) {
        authenticateClient(clientId, clientSecret);
        if (code == null || code.isBlank()) {
            throw OAuthException.invalidRequest("code is required");
        }
        AuthorizationCode stored = codes.consume(PlatformCrypto.sha256(code))
            .orElseThrow(() -> OAuthException.invalidGrant("invalid or already-used authorization code"));
        if (stored.isExpired(Instant.now())) {
            throw OAuthException.invalidGrant("authorization code expired");
        }
        if (!stored.clientId().equals(clientId)) {
            throw OAuthException.invalidGrant("authorization code was issued to another client");
        }
        if (!stored.redirectUri().equals(redirectUri)) {
            throw OAuthException.invalidGrant("redirect_uri does not match the authorization request");
        }
        if (!Pkce.verify(codeVerifier, stored.codeChallenge(), stored.codeChallengeMethod())) {
            throw OAuthException.invalidGrant("PKCE verification failed");
        }
        return tokens.issue(
            stored.userId(), stored.userEmail(), stored.userName(), clientId, stored.scopes());
    }

    // refresh_token grant at /oauth/token.
    public IssuedTokens refresh(String clientId, String clientSecret, String refreshToken) {
        authenticateClient(clientId, clientSecret);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw OAuthException.invalidRequest("refresh_token is required");
        }
        try {
            return tokens.refresh(refreshToken, clientId);
        } catch (InvalidRefreshTokenException e) {
            throw OAuthException.invalidGrant(e.getMessage());
        }
    }

    public void revoke(String token) {
        // Best-effort per RFC 7009: an unknown/other-typed token is a no-op with
        // a 200. Only refresh tokens are opaque and revocable here (access tokens
        // are stateless and simply expire).
        if (token != null && !token.isBlank()) {
            tokens.revoke(token);
        }
    }

    // --- Client authentication. ---

    // A confidential client must present a matching secret; a public client is
    // authenticated by PKCE alone (verified during the code exchange), so no
    // secret is required. Either way an unknown client fails.
    private OAuthClient authenticateClient(String clientId, String clientSecret) {
        OAuthClient client = clients.findById(clientId)
            .orElseThrow(() -> OAuthException.invalidClient("unknown client"));
        if (client.isConfidential()) {
            if (clientSecret == null
                || !PlatformCrypto.constantTimeEquals(
                    client.secretHash(), PlatformCrypto.sha256(clientSecret))) {
                throw OAuthException.invalidClient("bad client credentials");
            }
        }
        return client;
    }

    private void persistGrant(String userId, String clientId, Set<String> scopes, Instant now) {
        Set<String> union = new LinkedHashSet<>(scopes);
        grants.find(userId, clientId).ifPresent(existing -> union.addAll(existing.scopes()));
        Instant grantedAt = grants.find(userId, clientId).map(OAuthGrant::grantedAt).orElse(now);
        grants.save(new OAuthGrant(userId, clientId, union, grantedAt, now));
    }
}
