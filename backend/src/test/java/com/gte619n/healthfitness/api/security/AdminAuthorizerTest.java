package com.gte619n.healthfitness.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Phase-6: admin authorization must not trust an unverified email claim. An
 * allow-listed email only grants admin when the token asserts email_verified.
 */
class AdminAuthorizerTest {

    private static final String ADMIN = "admin@example.com";

    // Mirrors the real provider: resolve the email from the current JWT.
    private final CurrentUserProvider provider = () -> {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new CurrentUser(
                jwt.getToken().getSubject(),
                jwt.getToken().getClaimAsString("email"),
                null, null);
        }
        return new CurrentUser("sub", null, null, null);
    };
    private final AdminAuthorizer authorizer = new AdminAuthorizer(provider, ADMIN);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String email, Object emailVerified) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("sub-1")
            .claim("email", email);
        if (emailVerified != null) {
            builder.claim("email_verified", emailVerified);
        }
        SecurityContextHolder.getContext()
            .setAuthentication(new JwtAuthenticationToken(builder.build()));
    }

    @Test
    void allowlistedEmailWithVerifiedClaimIsAdmin() {
        authenticate(ADMIN, true);
        assertThat(authorizer.isAdmin()).isTrue();
    }

    @Test
    void allowlistedEmailWithUnverifiedClaimIsRejected() {
        authenticate(ADMIN, false);
        assertThat(authorizer.isAdmin()).isFalse();
    }

    @Test
    void allowlistedEmailWithNoVerifiedClaimIsRejected() {
        authenticate(ADMIN, null);
        assertThat(authorizer.isAdmin()).isFalse();
    }

    @Test
    void nonAllowlistedEmailIsNeverAdmin() {
        authenticate("stranger@example.com", true);
        assertThat(authorizer.isAdmin()).isFalse();
    }
}
