package com.gte619n.healthfitness.feature.settings.units

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.SegmentedChoice
import com.gte619n.healthfitness.ui.components.SettingsCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun UnitsSection(
    viewModel: UnitsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    SettingsCard(title = "Units") {
        UnitRow(
            label = "Height",
            options = listOf(
                HeightUnit.FEET_INCHES to "ft / in",
                HeightUnit.CENTIMETERS to "cm",
            ),
            selected = prefs.height,
            onSelect = viewModel::setHeight,
        )
        UnitRow(
            label = "Weight",
            options = listOf(
                WeightUnit.POUNDS to "lb",
                WeightUnit.KILOGRAMS to "kg",
            ),
            selected = prefs.weight,
            onSelect = viewModel::setWeight,
        )
        UnitRow(
            label = "Temperature",
            options = listOf(
                TemperatureUnit.FAHRENHEIT to "°F",
                TemperatureUnit.CELSIUS to "°C",
            ),
            selected = prefs.temperature,
            onSelect = viewModel::setTemperature,
        )
    }
}

@Composable
private fun <T> UnitRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        SegmentedChoice(options = options, selected = selected, onSelect = onSelect)
    }
}
