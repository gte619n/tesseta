package com.gte619n.healthfitness.domain.profile

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
    /**
     * IMPL-MULTIUSER-01 P1.8 — whether the backend considers this user an admin
     * (role ADMIN or a bootstrap-admin email). Gates the owner-only affordances
     * (e.g. the active-workout demo-frame flag) that used to key off a hardcoded
     * OWNER_EMAILS set. Defaults false so pre-field payloads read as non-admin.
     */
    val isAdmin: Boolean = false,
)
