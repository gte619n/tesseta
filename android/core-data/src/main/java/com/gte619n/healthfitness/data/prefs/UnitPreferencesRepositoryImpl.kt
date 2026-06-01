package com.gte619n.healthfitness.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.unitsStore by preferencesDataStore("hf-units")

@Singleton
class UnitPreferencesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : UnitPreferencesRepository {

    private val keyHeight = stringPreferencesKey("height_unit")
    private val keyWeight = stringPreferencesKey("weight_unit")
    private val keyTemperature = stringPreferencesKey("temperature_unit")

    override val preferences: Flow<UnitPreferences> = context.unitsStore.data.map { prefs ->
        UnitPreferences(
            height = prefs[keyHeight].toEnum(HeightUnit.FEET_INCHES, HeightUnit::valueOf),
            weight = prefs[keyWeight].toEnum(WeightUnit.POUNDS, WeightUnit::valueOf),
            temperature = prefs[keyTemperature].toEnum(TemperatureUnit.FAHRENHEIT, TemperatureUnit::valueOf),
        )
    }

    override suspend fun setHeightUnit(unit: HeightUnit) {
        context.unitsStore.edit { it[keyHeight] = unit.name }
    }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        context.unitsStore.edit { it[keyWeight] = unit.name }
    }

    override suspend fun setTemperatureUnit(unit: TemperatureUnit) {
        context.unitsStore.edit { it[keyTemperature] = unit.name }
    }

    private fun <T> String?.toEnum(default: T, parse: (String) -> T): T =
        this?.let { runCatching { parse(it) }.getOrNull() } ?: default
}
