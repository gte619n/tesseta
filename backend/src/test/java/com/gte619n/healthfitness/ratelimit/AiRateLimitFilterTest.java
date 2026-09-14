package com.gte619n.healthfitness.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.platform.PlatformRateLimitStore;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SEC-001 — the first-party AI limiter blocks a user after N Gemini-triggering
 * requests within a window, isolates users, and does not touch non-AI routes.
 */
class AiRateLimitFilterTest {

    // Deterministic in-memory store (mirrors InMemoryPlatformRateLimitStore).
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final PlatformRateLimitStore store = (key, window) ->
        counters.computeIfAbsent(key + "_" + window, k -> new AtomicLong()).incrementAndGet();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blocksAfterLimitThenIsolatesOtherUsers() throws Exception {
        AiRateLimitFilter filter =
            new AiRateLimitFilter(3, Duration.ofHours(1), store);

        authenticate("userA");
        // First 3 pass; 4th is 429 within the same window.
        assertThat(callCapture(filter)).isEqualTo(200);
        assertThat(callCapture(filter)).isEqualTo(200);
        assertThat(callCapture(filter)).isEqualTo(200);
        assertThat(callCapture(filter)).isEqualTo(429);

        // A different user has their own budget — first call passes.
        authenticate("userB");
        assertThat(callCapture(filter)).isEqualTo(200);
    }

    @Test
    void newWindowResetsTheBudget() throws Exception {
        // 1-second window so the bucket rolls over between calls.
        AiRateLimitFilter filter =
            new AiRateLimitFilter(1, Duration.ofSeconds(1), store);
        authenticate("userWindow");

        assertThat(callCapture(filter)).isEqualTo(200);
        assertThat(callCapture(filter)).isEqualTo(429);

        Thread.sleep(1100);
        assertThat(callCapture(filter)).isEqualTo(200);
    }

    @Test
    void nonAiRouteIsNeverLimited() throws Exception {
        AiRateLimitFilter filter =
            new AiRateLimitFilter(1, Duration.ofHours(1), store);
        authenticate("userC");

        // A plain (non-AI) POST passes regardless of count.
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest(
                "POST", "/api/me/nutrition/2026-09-14/entries");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void failsOpenWhenStoreThrows() throws Exception {
        PlatformRateLimitStore boom = (k, w) -> {
            throw new RuntimeException("firestore down");
        };
        AiRateLimitFilter filter = new AiRateLimitFilter(1, Duration.ofHours(1), boom);
        authenticate("userD");

        // Store failure must not block the user.
        assertThat(callCapture(filter)).isEqualTo(200);
        assertThat(callCapture(filter)).isEqualTo(200);
    }

    private int callCapture(AiRateLimitFilter filter) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(
            "POST", "/api/nutrition/capture/meal");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return res.getStatus();
    }

    private static void authenticate(String userId) {
        CurrentUser cu = new CurrentUser(userId, userId + "@example.com", userId, null);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            cu, "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
