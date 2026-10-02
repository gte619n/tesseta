package com.gte619n.healthfitness.core.user;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The {@code ADMIN_EMAILS} env allowlist, as a permanent bootstrap source of
 * admin identity (IMPL-MULTIUSER-01 P1.2, decision D-bootstrap). Two jobs:
 *
 * <ol>
 *   <li>When a brand-new user is provisioned, {@link UserService} seeds the
 *       {@code ADMIN} role if their verified email is listed here.</li>
 *   <li>{@code AdminAuthorizer} and the account-status lockout treat a listed
 *       email as admin / never-locked-out even if the stored record is wrong —
 *       so a data bug can never brick the owner out of their own admin console.</li>
 * </ol>
 *
 * Sourced from the same {@code app.admin.emails} property the former
 * email-only authorizer used, so existing deploy config keeps working.
 */
@Component
public class AdminBootstrap {

    private final Set<String> emails;

    public AdminBootstrap(@Value("${app.admin.emails:}") String adminEmailsCsv) {
        this.emails = parse(adminEmailsCsv);
    }

    static Set<String> parse(String csv) {
        if (csv == null) return Set.of();
        return Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isBootstrapAdmin(String email) {
        return email != null && emails.contains(email);
    }

    public Set<String> emails() {
        return emails;
    }
}
