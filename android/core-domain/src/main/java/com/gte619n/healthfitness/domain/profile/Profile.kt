package com.gte619n.healthfitness.domain.profile

/**
 * User profile snapshot, sourced from the backend's `GET /api/me`.
 *
 * `heightCm` is the only mutable field today; the rest mirror the
 * Google identity payload baked into the JWT.
 */
data class Profile(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val heightCm: Int?,
)
