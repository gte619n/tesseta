package com.gte619n.healthfitness.platform;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

// Records every /v1 request to the platform audit log after it completes
// (ADR-0020, D8). Placed outermost of the /v1 filters so it captures the final
// status — including a 429 from the rate limiter or a 403 from scope denial.
public class V1AuditFilter extends OncePerRequestFilter {

    private final PlatformAuditLogger audit;

    public V1AuditFilter(PlatformAuditLogger audit) {
        this.audit = audit;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            String clientId = null;
            String userId = null;
            String scope = null;
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                clientId = jwt.getClaimAsString("client_id");
                userId = jwt.getSubject();
                scope = jwt.getClaimAsString("scope");
            }
            audit.record(clientId, userId, scope,
                request.getMethod(), request.getRequestURI(), response.getStatus());
        }
    }
}
