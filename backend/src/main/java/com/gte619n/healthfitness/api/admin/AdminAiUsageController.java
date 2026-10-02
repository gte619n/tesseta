package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.ai.AiFeature;
import com.gte619n.healthfitness.core.ai.AiUsageStore;
import com.gte619n.healthfitness.core.ai.AiUsageSummary;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only AI usage analytics (IMPL-MULTIUSER-01 Pillar 2, P2.3, D4).
 *
 * <p>All reads come from the monthly rollup docs (no raw-event scans on the hot
 * path). Internal-ops only — there is no user-facing surface.
 */
@RestController
@RequestMapping("/api/admin/ai-usage")
@AdminOnly
public class AdminAiUsageController {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final int DEFAULT_TOP = 20;
    private static final int DEFAULT_TREND_MONTHS = 12;

    private final AiUsageStore store;

    public AdminAiUsageController(AiUsageStore store) {
        this.store = store;
    }

    /**
     * Global summary for a month + per-feature totals + cost-over-time trend for
     * the preceding N months (default 12, inclusive of {@code month}).
     */
    @GetMapping
    public GlobalUsageResponse global(
        @RequestParam(required = false) String month,
        @RequestParam(required = false) Integer trendMonths
    ) {
        String ym = resolveMonth(month);
        int months = (trendMonths == null || trendMonths <= 0) ? DEFAULT_TREND_MONTHS : trendMonths;

        AiUsageSummary summary = store.monthlyGlobal(ym).orElseGet(() -> empty(AiUsageSummary.GLOBAL, ym));

        List<MonthlyCostPoint> trend = new ArrayList<>();
        YearMonth anchor = YearMonth.parse(ym, MONTH);
        for (int i = months - 1; i >= 0; i--) {
            String key = anchor.minusMonths(i).format(MONTH);
            // Reuse the already-fetched summary for the anchor month.
            Optional<AiUsageSummary> s = key.equals(ym)
                ? Optional.of(summary) : store.monthlyGlobal(key);
            trend.add(new MonthlyCostPoint(
                key,
                s.map(AiUsageSummary::totalCostUsd).orElse(0.0),
                s.map(AiUsageSummary::totalCalls).orElse(0L)));
        }

        return new GlobalUsageResponse(ym, toSummaryDto(summary), trend);
    }

    /** Top-spending users for a month, descending by cost. */
    @GetMapping("/top")
    public List<SummaryDto> top(
        @RequestParam(required = false) String month,
        @RequestParam(required = false) Integer limit
    ) {
        String ym = resolveMonth(month);
        int cap = (limit == null || limit <= 0) ? DEFAULT_TOP : limit;
        List<SummaryDto> out = new ArrayList<>();
        for (AiUsageSummary s : store.topSpenders(ym, cap)) {
            out.add(toSummaryDto(s));
        }
        return out;
    }

    /** Per-user drilldown for a month. */
    @GetMapping("/users/{userId}")
    public SummaryDto user(
        @PathVariable String userId,
        @RequestParam(required = false) String month
    ) {
        String ym = resolveMonth(month);
        AiUsageSummary summary = store.monthlyForUser(userId, ym).orElseGet(() -> empty(userId, ym));
        return toSummaryDto(summary);
    }

    // ---- helpers ----

    private static String resolveMonth(String month) {
        if (month != null && !month.isBlank()) {
            // Validate the yyyy-MM shape; throws if malformed (→ 400 via Spring).
            YearMonth.parse(month.strip(), MONTH);
            return month.strip();
        }
        return YearMonth.now(ZoneOffset.UTC).format(MONTH);
    }

    private static AiUsageSummary empty(String scope, String ym) {
        return new AiUsageSummary(scope, ym, 0, 0, 0, 0, 0.0, Map.of());
    }

    private static SummaryDto toSummaryDto(AiUsageSummary s) {
        Map<String, FeatureUsageDto> byFeature = new LinkedHashMap<>();
        if (s.byFeature() != null) {
            for (Map.Entry<AiFeature, AiUsageSummary.FeatureUsage> e : s.byFeature().entrySet()) {
                AiUsageSummary.FeatureUsage f = e.getValue();
                byFeature.put(e.getKey().name(), new FeatureUsageDto(
                    f.calls(), f.inputTokens(), f.outputTokens(), f.images(), f.costUsd()));
            }
        }
        return new SummaryDto(
            s.scope(),
            s.yearMonth(),
            s.totalCalls(),
            s.totalInputTokens(),
            s.totalOutputTokens(),
            s.totalImages(),
            s.totalCostUsd(),
            byFeature
        );
    }

    // ---- DTOs ----

    public record GlobalUsageResponse(
        String month,
        SummaryDto summary,
        List<MonthlyCostPoint> costOverTime
    ) {}

    public record MonthlyCostPoint(String month, double costUsd, long calls) {}

    public record SummaryDto(
        String scope,
        String yearMonth,
        long totalCalls,
        long totalInputTokens,
        long totalOutputTokens,
        long totalImages,
        double totalCostUsd,
        Map<String, FeatureUsageDto> byFeature
    ) {}

    public record FeatureUsageDto(
        long calls,
        long inputTokens,
        long outputTokens,
        long images,
        double costUsd
    ) {}
}
