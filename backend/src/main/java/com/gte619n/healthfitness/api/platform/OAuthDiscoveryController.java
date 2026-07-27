package com.gte619n.healthfitness.api.platform;

import com.gte619n.healthfitness.platform.AppPlatformProperties;
import com.gte619n.healthfitness.platform.PlatformKeys;
import com.gte619n.healthfitness.platform.PlatformScope;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

// Public discovery for the platform authorization server (ADR-0020):
//   GET /.well-known/oauth-authorization-server  — RFC 8414 metadata
//   GET /oauth/jwks.json                          — RS256 public keys
// Both are unauthenticated so an integrator's OAuth library can bootstrap.
@RestController
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class OAuthDiscoveryController {

    private final AppPlatformProperties props;
    private final PlatformKeys keys;

    public OAuthDiscoveryController(AppPlatformProperties props, PlatformKeys keys) {
        this.props = props;
        this.keys = keys;
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> metadata() {
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String authorizationEndpoint = props.getConsentPageUrl() != null
            && !props.getConsentPageUrl().isBlank()
            ? props.getConsentPageUrl()
            : base + "/oauth/authorize";
        return Map.ofEntries(
            Map.entry("issuer", props.getIssuer()),
            Map.entry("authorization_endpoint", authorizationEndpoint),
            Map.entry("token_endpoint", base + "/oauth/token"),
            Map.entry("revocation_endpoint", base + "/oauth/revoke"),
            Map.entry("userinfo_endpoint", base + "/oauth/userinfo"),
            Map.entry("jwks_uri", base + "/oauth/jwks.json"),
            Map.entry("scopes_supported", scopesSupported()),
            Map.entry("response_types_supported", List.of("code")),
            Map.entry("grant_types_supported", List.of("authorization_code", "refresh_token")),
            Map.entry("code_challenge_methods_supported", List.of("S256", "plain")),
            Map.entry("token_endpoint_auth_methods_supported",
                List.of("client_secret_post", "none"))
        );
    }

    @GetMapping("/oauth/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwks();
    }

    private static List<String> scopesSupported() {
        return Arrays.stream(PlatformScope.values()).map(PlatformScope::wire).toList();
    }
}
