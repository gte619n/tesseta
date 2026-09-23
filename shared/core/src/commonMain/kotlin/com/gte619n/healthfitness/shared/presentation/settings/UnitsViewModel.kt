package com.gte619n.healthfitness.shared.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.prefs.HeightUnit
import com.gte619n.healthfitness.shared.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.shared.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave A2 — shared port of the Android
 * `feature-settings/.../units/UnitsViewModel.kt`. Reactive on-device unit
 * preferences (height / weight / temperature). Identical to Android.
 */
class UnitsViewModel(
    private val repo: UnitPreferencesRepository,
) : ViewModel() {

    val preferences: StateFlow<UnitPreferences> = repo.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UnitPreferences(),
    )

    fun setHeight(unit: HeightUnit) {
        viewModelScope.launch { repo.setHeightUnit(unit) }
    }

    fun setWeight(unit: WeightUnit) {
        viewModelScope.launch { repo.setWeightUnit(unit) }
    }

    fun setTemperature(unit: TemperatureUnit) {
        viewModelScope.launch { repo.setTemperatureUnit(unit) }
    }
}
