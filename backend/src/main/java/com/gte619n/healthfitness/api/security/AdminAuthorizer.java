package com.gte619n.healthfitness.api.security;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
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
        return email != null && adminEmails.contains(email);
    }
}
