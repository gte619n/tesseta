package com.gte619n.healthfitness.core.ai;

import java.time.Instant;

/**
 * One recorded Gemini call (IMPL-MULTIUSER-01 Pillar 2). Written to the
 * top-level {@code aiUsageEvents/{eventId}} collection (90-day retention) and
 * folded into the per-user + global monthly rollups.
 *
 * <p>{@code userId} is the triggering user (D5) or {@link #SYSTEM} when there
 * is genuinely no originating user (background/system work). {@code status}
 * records whether the underlying call succeeded or threw.
 *
 * @param eventId          unique id for this event (also the Firestore doc id)
 * @param userId           triggering user's id, or {@link #SYSTEM}
 * @param feature          which AI feature produced the call
 * @param model            the Gemini model name used
 * @param inputTokens      prompt token count (0 when usage metadata absent)
 * @param outputTokens     candidates token count (0 when usage metadata absent)
 * @param images           number of generated images (image features)
 * @param estimatedCostUsd estimated USD cost from the pricing table
 * @param status           {@link Status#SUCCESS} or {@link Status#ERROR}
 * @param startedAt        when the call started
 * @param timestamp        when the event was recorded (call completion)
 * @param streaming        true if the call was a streaming generate
 * @param requestId        best-effort correlation id (may be null)
 */
public record AiUsageEvent(
    String eventId,
    String userId,
    AiFeature feature,
    String model,
    long inputTokens,
    long outputTokens,
    long images,
    double estimatedCostUsd,
    Status status,
    Instant startedAt,
    Instant timestamp,
    boolean streaming,
    String requestId
) {

    /** Attribution bucket when no originating user exists (D5). */
    public static final String SYSTEM = "SYSTEM";

    public enum Status {
        SUCCESS,
        ERROR
    }
}
