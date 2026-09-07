package com.gte619n.healthfitness.api.biometrics;

import java.time.Instant;

// One row in the biometrics settings section: the metric's identity + visibility
// plus its latest reading and how often it arrives over the last 60 days.
//
// latestValue/latestAt are null when there is no reading in the window.
// observationsLast60d is the count of readings in the window; avgIntervalDays is
// 60 / count (≈ "one every N days"), null when there are no readings. Clients
// format the raw value per the user's unit preference and derive a weekly rate
// if they prefer (7 / avgIntervalDays).
public record BiometricSummary(
    String key,
    String label,
    String unit,
    boolean visible,
    Double latestValue,
    Instant latestAt,
    int observationsLast60d,
    Double avgIntervalDays
) {}
