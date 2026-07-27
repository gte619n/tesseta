package com.gte619n.healthfitness.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.gte619n.healthfitness.core.platform.OAuthClient;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.platform.OAuthAuthorizationService.AuthorizationRequest;
import com.gte619n.healthfitness.testsupport.InMemoryAuthorizationCodeStore;
import com.gte619n.healthfitness.testsupport.InMemoryOAuthClientStore;
import com.gte619n.healthfitness.testsupport.InMemoryOAuthGrantStore;
import com.gte619n.healthfitness.testsupport.InMemoryPlatformRefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Unit coverage for the ADR-0020 authorization-server core: request validation,
// PKCE-bound single-use codes, client authentication, and the token exchange.
class OAuthAuthorizationServiceTest {

    // RFC 7636 Appendix B PKCE pair.
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private static final String REDIRECT = "https://app.example.com/callback";
    private static final Set<String> CLIENT_SCOPES =
        Set.of("workouts:read", "profile:read", "offline_access");

    private InMemoryOAuthClientStore clients;
    private InMemoryOAuthGrantStore grants;
    private OAuthAuthorizationService service;

    @BeforeEach
    void setUp() {
        AppPlatformProperties props = new AppPlatformProperties();
        props.setIssuer("tesseta-platform");
        props.setAudience("tesseta-platform-api");
        props.setAccessTtl(Duration.ofMinutes(15));
        props.setRefreshTtl(Duration.ofDays(60));
        props.setCodeTtl(Duration.ofMinutes(5));

        PlatformKeys keys = new PlatformKeys(props);
        clients = new InMemoryOAuthClientStore();
        grants = new InMemoryOAuthGrantStore();
        InMemoryAuthorizationCodeStore codes = new InMemoryAuthorizationCodeStore();
        InMemoryPlatformRefreshTokenStore refreshTokens = new InMemoryPlatformRefreshTokenStore();
        InMemoryUserRepository users = new InMemoryUserRepository();
        users.save(new User("user-1", "ada@example.com", "Ada Lovelace", null, null,
            Instant.now(), Instant.now()));

        PlatformTokenService tokenService =
            new PlatformTokenService(props, keys, refreshTokens, users);
        service = new OAuthAuthorizationService(props, clients, codes, grants, tokenService);

        clients.save(new OAuthClient("cli_pub", "Coach Monitor", "https://logo",
            List.of(REDIRECT), CLIENT_SCOPES, null, Instant.now()));
        clients.save(new OAuthClient("cli_conf", "Clinic Dashboard", null,
            List.of(REDIRECT), CLIENT_SCOPES, PlatformCrypto.sha256("s3cret-value"), Instant.now()));
    }

    private AuthorizationRequest validate(String clientId, String redirect, String scope) {
        return service.validate("user-1", clientId, redirect, "code", scope, CHALLENGE, "S256", "st");
    }

    // --- Happy path: authorize -> consent -> token. ---

    @Test
    void publicClientAuthorizationCodeFlowWithPkceIssuesTokens() {
        AuthorizationRequest req = validate("cli_pub", REDIRECT, "workouts:read offline_access");
        assertThat(req.scopes()).containsExactlyInAnyOrder("workouts:read", "offline_access");
        assertThat(req.previouslyGranted()).isFalse();

        String code = service.issueCode(req, "user-1", "ada@example.com", "Ada Lovelace");
        assertThat(grants.find("user-1", "cli_pub")).isPresent();

        IssuedTokens tokens =
            service.exchangeAuthorizationCode("cli_pub", null, code, REDIRECT, VERIFIER);
        assertThat(tokens.accessToken()).isNotBlank();
        assertThat(tokens.refreshToken()).isNotBlank(); // offline_access granted
    }

    @Test
    void confidentialClientRequiresItsSecret() {
        AuthorizationRequest req = validate("cli_conf", REDIRECT, "workouts:read");
        String code = service.issueCode(req, "user-1", null, null);

        assertThat(service.exchangeAuthorizationCode(
            "cli_conf", "s3cret-value", code, REDIRECT, VERIFIER).accessToken()).isNotBlank();
    }

    @Test
    void confidentialClientWithWrongSecretIsRejected() {
        AuthorizationRequest req = validate("cli_conf", REDIRECT, "workouts:read");
        String code = service.issueCode(req, "user-1", null, null);

        OAuthException e = catchThrowableOfType(
            () -> service.exchangeAuthorizationCode("cli_conf", "wrong", code, REDIRECT, VERIFIER),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_client");
    }

    // --- Validation guards. ---

    @Test
    void unknownClientIsInvalidClient() {
        OAuthException e = catchThrowableOfType(
            () -> validate("cli_nope", REDIRECT, "workouts:read"), OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_client");
    }

    @Test
    void unregisteredRedirectUriIsRejectedAndNeverRedirected() {
        OAuthException e = catchThrowableOfType(
            () -> validate("cli_pub", "https://evil.example/cb", "workouts:read"),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_request");
    }

    @Test
    void scopeOutsideTheClientAllowlistIsInvalidScope() {
        // labs:read is a real scope but not in this client's allowlist.
        OAuthException e = catchThrowableOfType(
            () -> validate("cli_pub", REDIRECT, "labs:read"), OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_scope");
    }

    @Test
    void missingCodeChallengeIsRejected() {
        OAuthException e = catchThrowableOfType(
            () -> service.validate("user-1", "cli_pub", REDIRECT, "code",
                "workouts:read", null, "S256", "st"),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_request");
    }

    // --- Token-exchange guards. ---

    @Test
    void wrongPkceVerifierIsInvalidGrant() {
        AuthorizationRequest req = validate("cli_pub", REDIRECT, "workouts:read");
        String code = service.issueCode(req, "user-1", null, null);

        OAuthException e = catchThrowableOfType(
            () -> service.exchangeAuthorizationCode("cli_pub", null, code, REDIRECT, "wrong-verifier"),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_grant");
    }

    @Test
    void codeIsSingleUse() {
        AuthorizationRequest req = validate("cli_pub", REDIRECT, "workouts:read");
        String code = service.issueCode(req, "user-1", null, null);

        service.exchangeAuthorizationCode("cli_pub", null, code, REDIRECT, VERIFIER);
        OAuthException e = catchThrowableOfType(
            () -> service.exchangeAuthorizationCode("cli_pub", null, code, REDIRECT, VERIFIER),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_grant");
    }

    @Test
    void redirectUriMismatchAtExchangeIsInvalidGrant() {
        AuthorizationRequest req = validate("cli_pub", REDIRECT, "workouts:read");
        String code = service.issueCode(req, "user-1", null, null);

        OAuthException e = catchThrowableOfType(
            () -> service.exchangeAuthorizationCode(
                "cli_pub", null, code, "https://app.example.com/other", VERIFIER),
            OAuthException.class);
        assertThat(e.error()).isEqualTo("invalid_grant");
    }

    @Test
    void refreshGrantMintsFreshTokensForThePublicClient() {
        AuthorizationRequest req = validate("cli_pub", REDIRECT, "workouts:read offline_access");
        String code = service.issueCode(req, "user-1", null, null);
        IssuedTokens first =
            service.exchangeAuthorizationCode("cli_pub", null, code, REDIRECT, VERIFIER);

        IssuedTokens refreshed = service.refresh("cli_pub", null, first.refreshToken());
        assertThat(refreshed.accessToken()).isNotBlank();
        assertThat(refreshed.refreshToken()).isNotEqualTo(first.refreshToken());
    }

    @Test
    void reauthorizationReportsPreviouslyGranted() {
        AuthorizationRequest first = validate("cli_pub", REDIRECT, "workouts:read");
        service.issueCode(first, "user-1", null, null);

        AuthorizationRequest again = validate("cli_pub", REDIRECT, "workouts:read");
        assertThat(again.previouslyGranted()).isTrue();
    }

    @Test
    void invalidRefreshTokenSurfacesAsInvalidGrant() {
        assertThatThrownBy(() -> service.refresh("cli_pub", null, "bogus.token"))
            .isInstanceOf(OAuthException.class);
    }
}
