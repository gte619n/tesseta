package com.gte619n.healthfitness.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.platform.PlatformTokenService.InvalidRefreshTokenException;
import com.gte619n.healthfitness.testsupport.InMemoryPlatformRefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Unit coverage for the ADR-0020 platform access/refresh tokens: the access
// token is a verifiable RS256 JWT carrying the delegated client + scopes, and
// refresh tokens rotate with the same single-use + benign-replay semantics as
// the first-party session tokens (ADR-0019) while preserving the grant.
class PlatformTokenServiceTest {

    private static final String CLIENT = "cli_abc";
    private static final Set<String> FULL_SCOPES =
        Set.of("workouts:read", "profile:read", "offline_access");

    private AppPlatformProperties props;
    private PlatformKeys keys;
    private InMemoryPlatformRefreshTokenStore store;
    private InMemoryUserRepository users;
    private PlatformTokenService service;
    private JwtDecoder accessDecoder;

    @BeforeEach
    void setUp() {
        props = new AppPlatformProperties();
        props.setIssuer("tesseta-platform");
        props.setAudience("tesseta-platform-api");
        props.setAccessTtl(Duration.ofMinutes(15));
        props.setRefreshTtl(Duration.ofDays(60));
        keys = new PlatformKeys(props); // blank PEM => ephemeral keypair
        store = new InMemoryPlatformRefreshTokenStore();
        users = new InMemoryUserRepository();
        users.save(new User("user-1", "ada@example.com", "Ada Lovelace", null, null,
            Instant.now(), Instant.now()));
        service = new PlatformTokenService(props, keys, store, users);
        accessDecoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    }

    @Test
    void accessTokenIsAVerifiableRs256JwtWithClientAndScopeClaims() {
        IssuedTokens issued = service.issue(
            "user-1", "ada@example.com", "Ada Lovelace", CLIENT, FULL_SCOPES);

        Jwt jwt = accessDecoder.decode(issued.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("tesseta-platform");
        assertThat(jwt.getAudience()).contains("tesseta-platform-api");
        assertThat(jwt.getClaimAsString("client_id")).isEqualTo(CLIENT);
        assertThat(jwt.getClaimAsString("scope").split(" "))
            .contains("workouts:read", "profile:read", "offline_access");
    }

    @Test
    void identityClaimsAppearOnlyWhenProfileScopeGranted() {
        IssuedTokens withProfile = service.issue(
            "user-1", "ada@example.com", "Ada", CLIENT, Set.of("profile:read"));
        assertThat(accessDecoder.decode(withProfile.accessToken()).getClaimAsString("email"))
            .isEqualTo("ada@example.com");

        IssuedTokens withoutProfile = service.issue(
            "user-1", "ada@example.com", "Ada", CLIENT, Set.of("workouts:read"));
        assertThat(accessDecoder.decode(withoutProfile.accessToken()).getClaimAsString("email"))
            .isNull();
    }

    @Test
    void refreshTokenIssuedOnlyWithOfflineAccess() {
        assertThat(service.issue("user-1", null, null, CLIENT, Set.of("workouts:read"))
            .refreshToken()).isNull();
        assertThat(service.issue("user-1", null, null, CLIENT,
            Set.of("workouts:read", "offline_access")).refreshToken()).isNotNull();
    }

    @Test
    void refreshRotatesAndPreservesTheGrant() {
        IssuedTokens first = service.issue("user-1", "ada@example.com", "Ada", CLIENT, FULL_SCOPES);

        IssuedTokens second = service.refresh(first.refreshToken(), CLIENT);

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(second.scopes()).containsExactlyInAnyOrderElementsOf(FULL_SCOPES);
        Jwt jwt = accessDecoder.decode(second.accessToken());
        assertThat(jwt.getClaimAsString("client_id")).isEqualTo(CLIENT);
        assertThat(jwt.getClaimAsString("scope")).contains("workouts:read");
    }

    @Test
    void benignReplayOfARotatedTokenIsHonouredViaTheSuccessorChain() {
        IssuedTokens first = service.issue("user-1", null, null, CLIENT, FULL_SCOPES);
        // First refresh succeeds; imagine its response was lost in flight.
        service.refresh(first.refreshToken(), CLIENT);
        // The client retries with the only token it still holds — the old one.
        IssuedTokens retried = service.refresh(first.refreshToken(), CLIENT);
        assertThat(retried.accessToken()).isNotBlank();
        assertThat(retried.refreshToken()).isNotBlank();
    }

    @Test
    void refreshTokenCannotBeRedeemedByAnotherClient() {
        IssuedTokens first = service.issue("user-1", null, null, CLIENT, FULL_SCOPES);
        assertThatThrownBy(() -> service.refresh(first.refreshToken(), "cli_other"))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokedTokenBurnsTheFamilyOnReplay() {
        IssuedTokens first = service.issue("user-1", null, null, CLIENT, FULL_SCOPES);
        service.revoke(first.refreshToken());
        assertThatThrownBy(() -> service.refresh(first.refreshToken(), CLIENT))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
