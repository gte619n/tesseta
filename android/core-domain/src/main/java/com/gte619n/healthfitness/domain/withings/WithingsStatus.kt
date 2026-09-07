package com.gte619n.healthfitness.domain.withings

// Domain model of the Withings connection state, mirroring GoogleHealthStatus.
// needsReconnect is true when the stored (rotating) refresh token has died and
// the user must re-authorize; brokenReason is a short diagnostic.
data class WithingsStatus(
    val connected: Boolean,
    val connectedAtEpochSeconds: Long?,
    val needsReconnect: Boolean = false,
    val brokenReason: String? = null,
)
