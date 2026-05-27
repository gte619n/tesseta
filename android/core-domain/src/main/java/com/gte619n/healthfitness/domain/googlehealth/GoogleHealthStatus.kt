package com.gte619n.healthfitness.domain.googlehealth

/**
 * Connection state of the per-user Google Health integration. `connected`
 * is the source of truth for which UI branch to render; the timestamp is
 * informational only ("Connected May 26").
 */
data class GoogleHealthStatus(
    val connected: Boolean,
    val connectedAtEpochSeconds: Long?,
)
