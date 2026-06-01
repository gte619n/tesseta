package com.gte619n.healthfitness.domain.prefs

import kotlinx.coroutines.flow.Flow

/** Reads/writes the user's display-unit preferences. */
interface UnitPreferencesRepository {
    val preferences: Flow<UnitPreferences>

    suspend fun setHeightUnit(unit: HeightUnit)
    suspend fun setWeightUnit(unit: WeightUnit)
    suspend fun setTemperatureUnit(unit: TemperatureUnit)
}
