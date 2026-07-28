package com.gte619n.healthfitness.domain.googlehealth

data class GoogleHealthStatus(
    val connected: Boolean,
    val connectedAtEpochSeconds: Long?,
    // True when the connection exists but its refresh token has died — the user
    // must reconnect. brokenReason is a short server-provided diagnostic.
    val needsReconnect: Boolean = false,
    val brokenReason: String? = null,
)
