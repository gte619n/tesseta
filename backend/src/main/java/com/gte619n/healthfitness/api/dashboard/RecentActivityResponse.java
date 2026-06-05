package com.gte619n.healthfitness.api.dashboard;

import java.time.Instant;

/**
 * One row in the dashboard "Recent" feed — a single piece of activity (a
 * completed workout, a weigh-in, a night's sleep, a logged food, a medication
 * dose taken) reduced to a uniform shape.
 *
 * <p>The backend does the cross-source merge, newest-first sort, and cap so both
 * the web and Android clients render the same feed without re-implementing the
 * logic. {@code title}/{@code subtitle} are display-ready (weights baked to lb,
 * to match the app's convention); each client maps {@code kind} to its own icon
 * and tone and formats {@code timestamp} into a relative/clock label its own way.
 */
public record RecentActivityResponse(
    ActivityKind kind,
    String title,
    String subtitle,   // nullable — secondary line (e.g. "48 min · 18 sets")
    Instant timestamp
) {}
