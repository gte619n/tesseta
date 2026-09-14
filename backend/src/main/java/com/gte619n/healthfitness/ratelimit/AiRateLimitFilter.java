package com.gte619n.healthfitness.ratelimit;

import com.gte619n.healthfitness.core.platform.PlatformRateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * SEC-001 — per-user fixed-window rate limit on the first-party Gemini-backed
 * ("AI") endpoints. Mirrors {@link com.gte619n.healthfitness.platform.V1RateLimitFilter}
 * (same {@link PlatformRateLimitStore} backing) but keys by the first-party user
 * (JWT {@code sub}) and matches only the expensive AI POST routes.
 *
 * <p><b>Fail-open</b> (availability over enforcement, per the finding): if the
 * store throws (e.g. Firestore hiccup) the request is allowed and the failure is
 * logged. Runs after authentication is populated so the principal is available.
 */
public class AiRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimitFilter.class);

    private final int limit;
    private final long windowSeconds;
    private final PlatformRateLimitStore store;

    public AiRateLimitFilter(int limit, Duration window, PlatformRateLimitStore store) {
        this.limit = Math.max(1, limit);
        this.windowSeconds = Math.max(1, window.getSeconds());
        this.store = store;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        if (!isAiEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }

        String key = keyFor(request);
        long nowEpoch = Instant.now().getEpochSecond();
        long windowStart = nowEpoch - (nowEpoch % windowSeconds);
        long resetSeconds = (windowStart + windowSeconds) - nowEpoch;

        long used;
        try {
            used = store.incrementAndGet(key, windowStart);
        } catch (RuntimeException e) {
            // Fail open: never block a legitimate user because the counter store
            // is unavailable — enforcement is a cost guard, not a correctness one.
            log.warn("AI rate-limit store unavailable; failing open for {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.toString());
            chain.doFilter(request, response);
            return;
        }

        int remaining = (int) Math.max(0, limit - used);
        response.setHeader("RateLimit-Limit", String.valueOf(limit));
        response.setHeader("RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("RateLimit-Reset", String.valueOf(resetSeconds));

        if (used > limit) {
            log.warn("AI rate limit exceeded for {} on {} {} ({} > {})",
                key, request.getMethod(), request.getRequestURI(), used, limit);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(resetSeconds));
            response.setContentType("application/problem+json");
            response.getWriter().write(
                "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                + "\"detail\":\"AI request limit reached; retry after " + resetSeconds + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * True for the Gemini-triggering POST routes only (SEC-001 scope): meal
     * capture/label, describe-a-meal, food image regenerate, and the per-entry
     * AI operations (reanalyze, adjust preview/start, leftovers analyze,
     * capture-meal, describe-meal[-async]), plus the goals + program-designer
     * chat streams. Cheap persistence POSTs (adjust/apply, adjust/commit, plain
     * entry create) are intentionally excluded.
     */
    static boolean isAiEndpoint(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("/api/nutrition/capture/")
            || uri.equals("/api/nutrition/describe")
            || uri.equals("/api/me/goals/chat")
            || uri.equals("/api/me/workout-programs/chat")) {
            return true;
        }
        if (uri.startsWith("/api/foods/") && uri.endsWith("/image/regenerate")) {
            return true;
        }
        // Per-entry AI operations under /api/me/nutrition/{date}/entries/{entryId}/...
        if (uri.startsWith("/api/me/nutrition/")) {
            return uri.endsWith("/reanalyze")
                || uri.endsWith("/adjust/preview")
                || uri.endsWith("/adjust/start")
                || uri.endsWith("/leftovers/analyze")
                || uri.endsWith("/capture-meal")
                || uri.endsWith("/describe-meal")
                || uri.endsWith("/describe-meal-async");
        }
        return false;
    }

    /** Per-user key: the first-party JWT subject, else the CurrentUser principal. */
    private static String keyFor(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            if (auth.getPrincipal() instanceof Jwt jwt) {
                return "ai:" + jwt.getSubject();
            }
            if (auth.getPrincipal()
                    instanceof com.gte619n.healthfitness.core.auth.CurrentUser cu) {
                return "ai:" + cu.userId();
            }
            if (auth.getName() != null) {
                return "ai:" + auth.getName();
            }
        }
        return "ai:anon:" + request.getRemoteAddr();
    }
}
