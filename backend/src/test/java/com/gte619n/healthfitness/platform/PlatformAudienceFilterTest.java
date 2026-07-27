package com.gte619n.healthfitness.platform;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

// The platform/first-party audience boundary (ADR-0020): a platform token is
// confined to /v1 + /oauth/userinfo, and /v1 rejects a first-party token.
class PlatformAudienceFilterTest {

    private static final String ISSUER = "tesseta-platform";
    private final PlatformAudienceFilter filter = new PlatformAudienceFilter(ISSUER);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String issuer) {
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "RS256")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("iss", issuer)
            .build();
        SecurityContextHolder.getContext().setAuthentication(
            new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    private int run(String uri) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        boolean passed = Mockito.mockingDetails(chain).getInvocations().stream()
            .anyMatch(i -> i.getMethod().getName().equals("doFilter"));
        return passed ? 200 : res.getStatus();
    }

    @Test
    void platformTokenIsBlockedOnTheFirstPartyApi() throws Exception {
        authenticateAs(ISSUER);
        assertThat(run("/api/me")).isEqualTo(403);
        assertThat(run("/api/me/connected-apps")).isEqualTo(403);
    }

    @Test
    void platformTokenIsAllowedInItsOwnZone() throws Exception {
        authenticateAs(ISSUER);
        assertThat(run("/v1/workouts")).isEqualTo(200);
        assertThat(run("/oauth/userinfo")).isEqualTo(200);
    }

    @Test
    void firstPartyTokenIsBlockedFromTheV1Api() throws Exception {
        authenticateAs("tesseta-backend");
        assertThat(run("/v1/workouts")).isEqualTo(403);
    }

    @Test
    void firstPartyTokenIsAllowedOnTheFirstPartyApi() throws Exception {
        authenticateAs("tesseta-backend");
        assertThat(run("/api/me")).isEqualTo(200);
    }

    @Test
    void unauthenticatedRequestsFallThroughToNormalAuthorization() throws Exception {
        // No authentication set; the filter must not block — the matcher layer
        // handles 401 for protected paths.
        assertThat(run("/v1/workouts")).isEqualTo(200);
    }

    @Test
    void nonJwtPrincipalIsTreatedAsFirstParty() throws Exception {
        // Dev-mode header auth (tests) yields a non-Jwt principal — never a
        // platform token, so it is never confined.
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("dev-user", null,
                AuthorityUtils.NO_AUTHORITIES));
        assertThat(run("/api/me")).isEqualTo(200);
        assertThat(run("/v1/workouts")).isEqualTo(403);
    }
}
