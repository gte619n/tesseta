package com.gte619n.healthfitness.api.platform;

import com.gte619n.healthfitness.platform.IssuedTokens;
import com.gte619n.healthfitness.platform.OAuthAuthorizationService;
import com.gte619n.healthfitness.platform.OAuthException;
import com.gte619n.healthfitness.platform.PlatformScope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The token + revocation endpoints (ADR-0020). Public (no bearer): the client
// authenticates with its own credentials (client_secret for confidential
// clients) and/or PKCE. Accepts the standard form-encoded OAuth parameters.
//
//   POST /oauth/token   grant_type=authorization_code | refresh_token
//   POST /oauth/revoke  RFC 7009
@RestController
@RequestMapping("/oauth")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthTokenController {

    private final OAuthAuthorizationService authService;

    public OAuthTokenController(OAuthAuthorizationService authService) {
        this.authService = authService;
    }

    @PostMapping(value = "/token")
    public ResponseEntity<TokenResponse> token(
        @RequestParam(name = "grant_type", required = false) String grantType,
        @RequestParam(name = "client_id", required = false) String clientId,
        @RequestParam(name = "client_secret", required = false) String clientSecret,
        @RequestParam(name = "code", required = false) String code,
        @RequestParam(name = "redirect_uri", required = false) String redirectUri,
        @RequestParam(name = "code_verifier", required = false) String codeVerifier,
        @RequestParam(name = "refresh_token", required = false) String refreshToken
    ) {
        if (clientId == null || clientId.isBlank()) {
            throw OAuthException.invalidRequest("client_id is required");
        }
        IssuedTokens tokens = switch (grantType == null ? "" : grantType) {
            case "authorization_code" -> authService.exchangeAuthorizationCode(
                clientId, clientSecret, code, redirectUri, codeVerifier);
            case "refresh_token" -> authService.refresh(clientId, clientSecret, refreshToken);
            default -> throw OAuthException.unsupportedGrantType(
                "grant_type must be authorization_code or refresh_token");
        };
        // Cache-control per RFC 6749 §5.1 — token responses must not be cached.
        return ResponseEntity.ok()
            .header("Cache-Control", "no-store")
            .header("Pragma", "no-cache")
            .body(TokenResponse.of(tokens));
    }

    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(
        @RequestParam(name = "token", required = false) String token,
        @RequestParam(name = "token_type_hint", required = false) String tokenTypeHint
    ) {
        // RFC 7009: always 200, even for an unknown token, so a client can't
        // probe token validity via this endpoint.
        authService.revoke(token);
        return ResponseEntity.ok().build();
    }

    public record TokenResponse(
        String access_token,
        String token_type,
        long expires_in,
        String refresh_token,
        String scope
    ) {
        static TokenResponse of(IssuedTokens t) {
            return new TokenResponse(
                t.accessToken(),
                "Bearer",
                t.accessTokenExpiresInSeconds(),
                t.refreshToken(), // null when offline_access was not granted
                PlatformScope.join(t.scopes()));
        }
    }
}
