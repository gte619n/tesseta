package com.gte619n.healthfitness.api.workoutprogram;

import java.util.List;

/**
 * Body of the resilient {@code POST .../last-sets}: the exerciseIds the client
 * already holds in its local session draft. Lets the "same as last time"
 * prefill resolve without the current session existing server-side — the
 * id-in-path {@code GET .../sessions/{scheduledId}/last-sets} variant 404s until
 * the session is persisted, which for an offline-first / ad-hoc session past the
 * program's materialized schedule is only at completion.
 */
public record LastSetsRequest(List<String> exerciseIds) {}
