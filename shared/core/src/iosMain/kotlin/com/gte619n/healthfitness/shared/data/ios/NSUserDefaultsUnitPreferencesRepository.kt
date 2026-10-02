package com.gte619n.healthfitness.shared.data.ios

import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.prefs.HeightUnit
import com.gte619n.healthfitness.shared.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.shared.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSUserDefaults

/**
 * IMPL-IOS-01 Phase 1C (first vertical slice) — the iOS concrete implementation
 * of [UnitPreferencesRepository]. On-device only (the server is unit-agnostic),
 * backed by `NSUserDefaults`, exactly as the Android side uses DataStore.
 *
 * The reactive `preferences` flow is a [MutableStateFlow] seeded from storage and
 * re-emitted on every `set*` — so a SwiftUI view observing it (via SKIE +
 * `ObservableViewModel`) re-renders immediately, and the choice survives relaunch.
 */
class NSUserDefaultsUnitPreferencesRepository(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : UnitPreferencesRepository {

    private val state = MutableStateFlow(load())
    override val preferences: Flow<UnitPreferences> = state.asStateFlow()

    override suspend fun setHeightUnit(unit: HeightUnit) =
        persist(state.value.copy(height = unit))

    override suspend fun setWeightUnit(unit: WeightUnit) =
        persist(state.value.copy(weight = unit))

    override suspend fun setTemperatureUnit(unit: TemperatureUnit) =
        persist(state.value.copy(temperature = unit))

    private fun persist(next: UnitPreferences) {
        defaults.setObject(next.height.name, KEY_HEIGHT)
        defaults.setObject(next.weight.name, KEY_WEIGHT)
        defaults.setObject(next.temperature.name, KEY_TEMPERATURE)
        state.value = next
    }

    private fun load(): UnitPreferences = UnitPreferences(
        height = defaults.stringForKey(KEY_HEIGHT)?.toHeightUnit() ?: HeightUnit.FEET_INCHES,
        weight = defaults.stringForKey(KEY_WEIGHT)?.toWeightUnit() ?: WeightUnit.POUNDS,
        temperature = defaults.stringForKey(KEY_TEMPERATURE)?.toTemperatureUnit()
            ?: TemperatureUnit.FAHRENHEIT,
    )

    // Tolerant parse: an unknown stored value falls back to the default rather
    // than throwing (enum entries can only shrink safely).
    private fun String.toHeightUnit() = HeightUnit.entries.firstOrNull { it.name == this }
    private fun String.toWeightUnit() = WeightUnit.entries.firstOrNull { it.name == this }
    private fun String.toTemperatureUnit() = TemperatureUnit.entries.firstOrNull { it.name == this }

    private companion object {
        const val KEY_HEIGHT = "hf.units.height"
        const val KEY_WEIGHT = "hf.units.weight"
        const val KEY_TEMPERATURE = "hf.units.temperature"
    }
}
