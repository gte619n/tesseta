package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.AdminBootstrap;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.UserStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Locks out non-{@link UserStatus#ACTIVE} accounts at the request boundary
 * (IMPL-MULTIUSER-01 P1.4). Runs after {@code UserProvisioningFilter}, so the
 * principal is populated and the {@code users/{sub}} doc exists.
 *
 * <p>Status is resolved via {@link UserRepository#findById} which is
 * {@code @Cacheable} by userId and evicted on any status change — so this is a
 * cache hit on the hot path yet enforces a suspension on the very next request
 * (decision D14: the userById cache makes "instant" affordable, so we read
 * through rather than trusting the token's status claim).
 *
 * <p>A bootstrap admin ({@code ADMIN_EMAILS}) is always allowed through, so a
 * data bug can never lock the owner out of the admin console.
 *
 * <p>Fail-open when the user/status can't be resolved (e.g. doc missing): we
 * only block on an explicit non-active status, never on uncertainty, to avoid
 * accidental mass lockouts.
 */
public class AccountStatusFilter extends OncePerRequestFilter {

    private final CurrentUserProvider currentUserProvider;
    private final UserRepository users;
    private final AdminBootstrap adminBootstrap;

    public AccountStatusFilter(
        CurrentUserProvider currentUserProvider,
        UserRepository users,
        AdminBootstrap adminBootstrap
    ) {
        this.currentUserProvider = currentUserProvider;
        this.users = users;
        this.adminBootstrap = adminBootstrap;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }

        UserStatus status = resolveStatus();
        if (status != null && !status.isActive()) {
            writeForbidden(response, status);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Null = allow (couldn't resolve / bootstrap admin / active). */
    private UserStatus resolveStatus() {
        CurrentUser current;
        try {
            current = currentUserProvider.get();
        } catch (RuntimeException e) {
            return null; // no resolvable principal — let downstream authz decide
        }
        if (current == null || current.userId() == null) {
            return null;
        }
        if (adminBootstrap.isBootstrapAdmin(current.email())) {
            return null; // owner lockout-proof
        }
        return users.findById(current.userId())
            .map(User::status)
            .orElse(null); // doc missing → fail open
    }

    private void writeForbidden(HttpServletResponse response, UserStatus status) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        String body = "{\"type\":\"" + status.problemType() + "\",\"status\":403,\"title\":\""
            + status.name() + "\"}";
        response.getWriter().write(body);
    }
}
