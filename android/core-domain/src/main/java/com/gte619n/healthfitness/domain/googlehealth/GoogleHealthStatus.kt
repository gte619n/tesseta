package com.gte619n.healthfitness.domain.googlehealth

data class GoogleHealthStatus(
    val connected: Boolean,
    val connectedAtEpochSeconds: Long?,
)
