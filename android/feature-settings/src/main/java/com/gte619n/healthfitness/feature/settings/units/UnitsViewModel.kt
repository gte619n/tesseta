package com.gte619n.healthfitness.feature.settings.units

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.UnitPreferences
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UnitsViewModel @Inject constructor(
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
