package com.gte619n.healthfitness.domain.biometrics

import java.time.Instant

// One metric row in the biometrics settings section: identity + visibility plus
// its latest reading and how often it arrives over the last 60 days. latestValue
// is in the canonical `unit`; the UI formats it for the user's unit preference.
// avgIntervalDays is "one every N days" (null when there are no readings).
data class BiometricSummary(
    val key: String,
    val label: String,
    val unit: String,
    val visible: Boolean,
    val latestValue: Double?,
    val latestAt: Instant?,
    val observationsLast60d: Int,
    val avgIntervalDays: Double?,
)
