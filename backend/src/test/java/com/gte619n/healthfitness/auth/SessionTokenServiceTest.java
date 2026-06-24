package com.gte619n.healthfitness.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.auth.SessionTokenService.InvalidRefreshTokenException;
import com.gte619n.healthfitness.auth.SessionTokenService.TokenPair;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.testsupport.InMemoryRefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// Unit coverage for the ADR-0010 session-token issuance: the access token is a
// verifiable HS256 JWT carrying the right identity, and refresh tokens rotate
// with single-use + theft-detection semantics.
class SessionTokenServiceTest {

    private static final String KEY = "unit-test-session-signing-key-0123456789";
    private static final CurrentUser ADA =
        new CurrentUser("sub-123", "ada@example.com", "Ada Lovelace", null);

    private InMemoryRefreshTokenStore store;
    private InMemoryUserRepository users;
    private AppSessionProperties props;
    private SessionTokenService service;
    private JwtDecoder accessDecoder;

    @BeforeEach
    void setUp() {
        store = new InMemoryRefreshTokenStore();
        users = new InMemoryUserRepository();
        users.save(new User("sub-123", "ada@example.com", "Ada Lovelace", null, null,
            Instant.now(), Instant.now()));
        props = props(Duration.ofDays(60));
        service = new SessionTokenService(props, store, users);
        accessDecoder = NimbusJwtDecoder
            .withSecretKey(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    }

    private AppSessionProperties props(Duration refreshTtl) {
        AppSessionProperties p = new AppSessionProperties();
        p.setSigningKey(KEY);
        p.setIssuer("tesseta-backend");
        p.setAccessTtl(Duration.ofHours(1));
        p.setRefreshTtl(refreshTtl);
        return p;
    }

    @Test
    void issuedAccessTokenIsAVerifiableJwtWithIdentityClaims() {
        TokenPair pair = service.issueFor(ADA);

        Jwt jwt = accessDecoder.decode(pair.accessToken());
        assertThat(jwt.getSubject()).isEqualTo("sub-123");
        // Read the raw claim — a non-URL issuer is intentional, and getIssuer()
        // would try to coerce it to a URL.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("tesseta-backend");
        assertThat(jwt.getClaimAsString("email")).isEqualTo("ada@example.com");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Ada Lovelace");
        assertThat(pair.refreshToken()).contains(".");
        assertThat(pair.accessTokenExpiresAt()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    void refreshRotatesAndIssuesAFreshUsablePair() {
        TokenPair first = service.issueFor(ADA);

        TokenPair second = service.refresh(first.refreshToken());

        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(accessDecoder.decode(second.accessToken()).getSubject()).isEqualTo("sub-123");
        // The rotated token still works for the next refresh.
        assertThat(service.refresh(second.refreshToken())).isNotNull();
    }

    @Test
    void replayingARotatedTokenAfterTheGraceWindowIsTreatedAsTheftAndBurnsTheFamily() {
        // A negative grace puts every replay past the window, so a re-presented
        // rotated token is unambiguously theft.
        props.setReuseGrace(Duration.ofSeconds(-1));
        TokenPair first = service.issueFor(ADA);
        TokenPair second = service.refresh(first.refreshToken());

        // Re-presenting the already-rotated first token: rejected...
        assertThatThrownBy(() -> service.refresh(first.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);

        // ...and the whole family is now revoked, so the live token dies too.
        assertThatThrownBy(() -> service.refresh(second.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void replayingARotatedTokenWithinTheGraceWindowReissuesWithoutBurningTheFamily() {
        // Default 30s grace: a benign retry of a refresh whose response was lost
        // in flight must succeed, not log the user out.
        TokenPair first = service.issueFor(ADA);
        TokenPair second = service.refresh(first.refreshToken());

        // Replaying the just-rotated token yields a fresh, usable pair...
        TokenPair retry = service.refresh(first.refreshToken());
        assertThat(accessDecoder.decode(retry.accessToken()).getSubject()).isEqualTo("sub-123");
        assertThat(retry.refreshToken()).isNotEqualTo(first.refreshToken());

        // ...and the family is intact: the live rotated token still refreshes,
        // and so does the pair handed back to the retry.
        assertThat(service.refresh(second.refreshToken())).isNotNull();
        assertThat(service.refresh(retry.refreshToken())).isNotNull();
    }

    @Test
    void aLoggedOutTokenIsNotResurrectedByTheGraceWindow() {
        // Logout is definitive even within the reuse-grace window: a stray retry
        // must not re-animate the session.
        TokenPair pair = service.issueFor(ADA);
        service.revoke(pair.refreshToken());

        assertThatThrownBy(() -> service.refresh(pair.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void expiredRefreshTokenIsRejected() {
        SessionTokenService shortLived =
            new SessionTokenService(props(Duration.ofSeconds(-10)), store, users);
        TokenPair pair = shortLived.issueFor(ADA);

        assertThatThrownBy(() -> shortLived.refresh(pair.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void tamperedSecretIsRejected() {
        TokenPair pair = service.issueFor(ADA);
        String tokenId = pair.refreshToken().substring(0, pair.refreshToken().indexOf('.'));

        assertThatThrownBy(() -> service.refresh(tokenId + ".not-the-real-secret"))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void malformedTokenIsRejected() {
        assertThatThrownBy(() -> service.refresh("no-dot-here"))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void revokedTokenCannotRefresh() {
        TokenPair pair = service.issueFor(ADA);

        service.revoke(pair.refreshToken());

        assertThatThrownBy(() -> service.refresh(pair.refreshToken()))
            .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
