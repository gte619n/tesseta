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
)
