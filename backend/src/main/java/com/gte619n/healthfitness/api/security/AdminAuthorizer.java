package com.gte619n.healthfitness.api.security;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Decides whether the current request's authenticated user is an admin, by
 * matching their email against the configured allowlist ({@code app.admin.emails},
 * a comma-separated list sourced from the {@code ADMIN_EMAILS} env var in prod).
 *
 * <p>Referenced from {@link AdminOnly} via
 * {@code @PreAuthorize("@adminAuthorizer.isAdmin()")}. Replaces the former
 * AdminCheckAspect, which hardcoded the allowlist in source.
 */
@Component("adminAuthorizer")
public class AdminAuthorizer {

    private final CurrentUserProvider currentUserProvider;
    private final Set<String> adminEmails;

    public AdminAuthorizer(
        CurrentUserProvider currentUserProvider,
        @Value("${app.admin.emails:}") String adminEmailsCsv
    ) {
        this.currentUserProvider = currentUserProvider;
        this.adminEmails = Arrays.stream(adminEmailsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    /** True when the current authenticated user's email is in the admin allowlist. */
    public boolean isAdmin() {
        String email = currentUserProvider.get().email();
        if (email == null || !adminEmails.contains(email)) {
            return false;
        }
        // Defence-in-depth: only honour the email for admin when the token asserts
        // it is verified. Google ID tokens for our client IDs set
        // email_verified=true; this guards against ever trusting an unverified
        // email claim (e.g. if a client id issuing self-asserted emails were ever
        // added to the audience). Dev-mode auth is non-JWT and test-only, so an
        // email match there is allowed through.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return isEmailVerified(jwt.getToken());
        }
        return true;
    }

    private static boolean isEmailVerified(Jwt jwt) {
        Object claim = jwt.getClaim("email_verified");
        if (claim instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(claim));
    }
}
