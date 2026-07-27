package com.gte619n.healthfitness.platform;

import com.gte619n.healthfitness.core.platform.PlatformRateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

// Per-(client, user) fixed-window rate limit for the /v1 API (ADR-0020, D7/D18).
// The counter lives in a PlatformRateLimitStore: Firestore-backed for exact
// cross-instance limits in production, or in-memory per-instance in tests/local.
// Emits the RateLimit-* headers on every /v1 response and a 429 (with
// Retry-After) once a window's budget is spent.
public class V1RateLimitFilter extends OncePerRequestFilter {

    private final int limit;
    private final long windowSeconds;
    private final PlatformRateLimitStore store;

    public V1RateLimitFilter(int limit, Duration window, PlatformRateLimitStore store) {
        this.limit = limit;
        this.windowSeconds = Math.max(1, window.getSeconds());
        this.store = store;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/v1/")) {
            chain.doFilter(request, response);
            return;
        }

        long nowEpoch = Instant.now().getEpochSecond();
        long windowStart = nowEpoch - (nowEpoch % windowSeconds);
        long used = store.incrementAndGet(keyFor(request), windowStart);
        int remaining = (int) Math.max(0, limit - used);
        long resetSeconds = (windowStart + windowSeconds) - nowEpoch;

        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("RateLimit-Reset", String.valueOf(resetSeconds));

        if (used > limit) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(resetSeconds));
            response.setContentType("application/problem+json");
            response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"rate limit exceeded; retry after " + resetSeconds + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String keyFor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("client_id") + ":" + jwt.getSubject();
        }
        return "anon:" + request.getRemoteAddr();
    }
}
