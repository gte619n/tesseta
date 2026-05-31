package com.gte619n.healthfitness.domain.profile

data class Profile(
    val userId: String,
    val email: String?,
    val displayName: String?,
    val heightCm: Int?,
)
