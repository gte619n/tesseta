package com.gte619n.healthfitness.shared.domain.profile

import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/profile/Profile.kt (D3: Moshi→kotlinx.serialization).
 *
 * Field names and nullability match the Android source 1:1.
 */
@Serializable
data class Profile(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val heightCm: Int?,
    /** "MALE" | "FEMALE" | null — feeds the Mifflin-St Jeor calorie estimate. */
    val biologicalSex: String? = null,
    /** ISO "YYYY-MM-DD" | null — feeds the Mifflin-St Jeor calorie estimate. */
    val dateOfBirth: String? = null,
    /** Google `picture` URL, served fresh from the backend; null when absent. */
    val photoUrl: String? = null,
    /** Metric keys the user has hidden from the dashboard (empty = all shown). */
    val hiddenBiometrics: List<String> = emptyList(),
)
