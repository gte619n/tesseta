package com.gte619n.healthfitness.core.ai;

import java.util.Map;

/**
 * A monthly rollup of AI usage (IMPL-MULTIUSER-01 Pillar 2), read for the admin
 * dashboard. One summary is a single rollup doc: either a per-user rollup
 * ({@code scope == userId}) or the global rollup ({@code scope ==}
 * {@link #GLOBAL}).
 *
 * @param scope             the user id this rollup belongs to, or {@link #GLOBAL}
 * @param yearMonth         the month, {@code yyyy-MM}
 * @param totalCalls        number of recorded calls
 * @param totalInputTokens  summed prompt tokens
 * @param totalOutputTokens summed candidate tokens
 * @param totalImages       summed generated images
 * @param totalCostUsd      summed estimated cost
 * @param byFeature         per-feature breakdown
 */
public record AiUsageSummary(
    String scope,
    String yearMonth,
    long totalCalls,
    long totalInputTokens,
    long totalOutputTokens,
    long totalImages,
    double totalCostUsd,
    Map<AiFeature, FeatureUsage> byFeature
) {

    /** Scope sentinel for the global (all-users) rollup. */
    public static final String GLOBAL = "GLOBAL";

    /** Per-feature slice of a monthly rollup. */
    public record FeatureUsage(
        long calls,
        long inputTokens,
        long outputTokens,
        long images,
        double costUsd
    ) {}
}
