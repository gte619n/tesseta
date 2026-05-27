package com.gte619n.healthfitness.feature.workouts.nav

import kotlinx.serialization.Serializable

/**
 * Type-safe nav routes for the workouts feature.
 *
 * The phone app's central `NavHost` (in `app/`) registers these
 * directly — same pattern as feature-blood / feature-body-composition.
 */
@Serializable
data object GymsListRoute

@Serializable
data object NewGymRoute

@Serializable
data class GymDetailRoute(val locationId: String)

@Serializable
data class EditGymRoute(val locationId: String)
