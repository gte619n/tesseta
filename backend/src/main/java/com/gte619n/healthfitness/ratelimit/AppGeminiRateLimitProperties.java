package com.gte619n.healthfitness.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SEC-001 — per-user fixed-window rate limit on the first-party, Gemini-backed
 * ("AI") endpoints. Open signup (any Google account auto-provisions) plus these
 * unbounded, expensive Gemini/Cloud-Run calls is a cost-abuse vector: a single
 * throwaway account can script unlimited meal captures / describe / adjust /
 * leftover / goals-chat / program-designer requests.
 *
 * <p>This is the first-party analogue of the {@code /v1} platform limiter
 * ({@code app.platform.rate-limit-*}); it reuses the same
 * {@link com.gte619n.healthfitness.core.platform.PlatformRateLimitStore} backing.
 *
 * <ul>
 *   <li>{@code enabled} — master switch (default true).</li>
 *   <li>{@code requests} — max Gemini-triggering requests per user per window.</li>
 *   <li>{@code window} — the fixed window (default 1h).</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.gemini.rate-limit")
public class AppGeminiRateLimitProperties {

    private boolean enabled = true;
    // Conservative default: 30 AI requests / user / hour. A real human capturing
    // meals + occasional describe/adjust stays well under this; a scripted abuser
    // is capped at 30 Gemini calls/hour instead of unbounded. Tunable per env.
    private int requests = 30;
    private Duration window = Duration.ofHours(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequests() {
        return requests;
    }

    public void setRequests(int requests) {
        this.requests = requests;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
