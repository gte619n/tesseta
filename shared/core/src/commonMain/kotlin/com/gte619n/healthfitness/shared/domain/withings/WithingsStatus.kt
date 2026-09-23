package com.gte619n.healthfitness.shared.domain.withings

import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — extracted from
 * android/core-domain/.../domain/withings/WithingsStatus.kt.
 *
 * Domain model of the Withings connection state, mirroring GoogleHealthStatus.
 * [needsReconnect] is true when the stored (rotating) refresh token has died and
 * the user must re-authorize; [brokenReason] is a short diagnostic. Field names
 * and nullability match the Android source 1:1.
 */
@Serializable
data class WithingsStatus(
    val connected: Boolean,
    val connectedAtEpochSeconds: Long?,
    val needsReconnect: Boolean = false,
    val brokenReason: String? = null,
)
