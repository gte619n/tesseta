package com.gte619n.healthfitness.core.goals.events;

import java.time.Instant;

/**
 * Published whenever a metric-bearing record is written (blood reading,
 * blood test panel, body composition measurement, adherence log, etc.).
 *
 * The event carries only the minimum needed for the Goals evaluator to
 * decide which Steps to re-check. Consumers must NOT assume the event
 * is delivered exactly once or in order — the Goals listener is
 * idempotent and re-evaluates from the current persisted state.
 */
public record MetricChangedEvent(
    String userId,
    String metricKey,
    Instant occurredAt
) {}
