package com.gte619n.healthfitness.api.security;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.AdminBootstrap;
import com.gte619n.healthfitness.core.user.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Decides whether the current request's authenticated user is an admin
 * (IMPL-MULTIUSER-01 P1.2). Admin status is now data-driven — the ADMIN role on
 * the {@code users/{sub}} record — with the {@code ADMIN_EMAILS} env allowlist
 * ({@link AdminBootstrap}) retained as a permanent bootstrap fallback so the
 * owner can never be locked out of the console by a data bug.
 *
 * <p>Referenced from {@link AdminOnly} via
 * {@code @PreAuthorize("@adminAuthorizer.isAdmin()")}; the seam is unchanged, so
 * every existing admin controller keeps working with zero edits.
 */
@Component("adminAuthorizer")
public class AdminAuthorizer {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository users;
    private final AdminBootstrap adminBootstrap;

    public AdminAuthorizer(
        CurrentUserProvider currentUserProvider,
        UserRepository users,
        AdminBootstrap adminBootstrap
    ) {
        this.currentUserProvider = currentUserProvider;
        this.users = users;
        this.adminBootstrap = adminBootstrap;
    }

    /** True when the current authenticated user holds ADMIN (by role or env bootstrap). */
    public boolean isAdmin() {
        CurrentUser current = currentUserProvider.get();
        if (current == null || current.userId() == null) {
            return false;
        }
        // Defence-in-depth: only honour the email-based bootstrap when the token
        // asserts the email is verified (Google ID tokens set email_verified=true).
        // Dev-mode auth is non-JWT and test-only, so it's allowed through.
        boolean emailVerified = isEmailVerifiedOrDevMode();

        if (emailVerified && adminBootstrap.isBootstrapAdmin(current.email())) {
            return true;
        }
        return users.findById(current.userId())
            .map(com.gte619n.healthfitness.core.user.User::isAdmin)
            .orElse(false);
    }

    private boolean isEmailVerifiedOrDevMode() {
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
