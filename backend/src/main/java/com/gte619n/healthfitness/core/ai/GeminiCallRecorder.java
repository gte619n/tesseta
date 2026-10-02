package com.gte619n.healthfitness.core.ai;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The single sink for AI usage events (IMPL-MULTIUSER-01 Pillar 2, D5/D6).
 *
 * <p>Every Gemini call site routes its token counts here (via the AOP aspect
 * when AOP is on the classpath, or a one-line call-site hook otherwise). This
 * service computes the estimated cost from {@link AiModelPricing}, attributes
 * the call to the triggering user (or {@link AiUsageEvent#SYSTEM} when there is
 * no originating user, per D5), writes the event and increments both monthly
 * rollups via {@link AiUsageStore}.
 *
 * <p><b>Fire-and-forget:</b> a recording failure (store down, no user context,
 * anything) is caught and logged — it must NEVER propagate and break the AI
 * call it is metering.
 */
@Service
public class GeminiCallRecorder {

    private static final Logger log = LoggerFactory.getLogger(GeminiCallRecorder.class);

    private final AiUsageStore store;
    private final AiModelPricing pricing;
    private final CurrentUserProvider currentUserProvider;

    public GeminiCallRecorder(
        AiUsageStore store,
        AiModelPricing pricing,
        CurrentUserProvider currentUserProvider
    ) {
        this.store = store;
        this.pricing = pricing;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Record a successful call, attributed to the current request's user (or
     * SYSTEM if none).
     */
    public void recordSuccess(
        AiFeature feature,
        String model,
        long inputTokens,
        long outputTokens,
        long images,
        boolean streaming,
        Instant startedAt
    ) {
        record(feature, model, inputTokens, outputTokens, images,
            AiUsageEvent.Status.SUCCESS, streaming, startedAt, null);
    }

    /**
     * Record a failed call (the underlying Gemini call threw). Tokens are
     * typically unknown on failure, so pass 0.
     */
    public void recordError(
        AiFeature feature,
        String model,
        boolean streaming,
        Instant startedAt
    ) {
        record(feature, model, 0, 0, 0,
            AiUsageEvent.Status.ERROR, streaming, startedAt, null);
    }

    /**
     * Record an event, resolving attribution and cost. Catches EVERYTHING —
     * attribution resolution, pricing, and the store write — so a metering
     * failure can never surface to the caller.
     *
     * @param resolvedUserId an explicit user id for background jobs, or null to
     *                       resolve from the request's {@link CurrentUser}
     */
    public void record(
        AiFeature feature,
        String model,
        long inputTokens,
        long outputTokens,
        long images,
        AiUsageEvent.Status status,
        boolean streaming,
        Instant startedAt,
        String resolvedUserId
    ) {
        try {
            String userId = resolvedUserId != null ? resolvedUserId : resolveUserId();
            double cost = pricing.estimateCostUsd(model, inputTokens, outputTokens, images);
            Instant now = Instant.now();
            AiUsageEvent event = new AiUsageEvent(
                UUID.randomUUID().toString(),
                userId,
                feature == null ? AiFeature.UNKNOWN : feature,
                model,
                inputTokens,
                outputTokens,
                images,
                cost,
                status == null ? AiUsageEvent.Status.SUCCESS : status,
                startedAt,
                now,
                streaming,
                null
            );
            store.recordEvent(event);
        } catch (RuntimeException e) {
            // Fire-and-forget: never let metering break the AI call (D6).
            log.warn("AI usage recording failed for feature={} model={}: {}",
                feature, model, e.getMessage());
        }
    }

    /**
     * Resolve the triggering user. {@link CurrentUserProvider#get()} throws when
     * there is no authenticated request (background jobs, startup), which we
     * treat as the SYSTEM bucket (D5).
     */
    private String resolveUserId() {
        try {
            CurrentUser user = currentUserProvider.get();
            if (user != null && user.userId() != null && !user.userId().isBlank()) {
                return user.userId();
            }
        } catch (RuntimeException ignored) {
            // No request-bound user -> SYSTEM.
        }
        return AiUsageEvent.SYSTEM;
    }
}
