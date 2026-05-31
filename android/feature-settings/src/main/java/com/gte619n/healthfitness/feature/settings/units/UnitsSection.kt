package com.gte619n.healthfitness.feature.settings.units

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.prefs.TemperatureUnit
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun UnitsSection(
    viewModel: UnitsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()

    HfCard(transparent = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Units", style = Hf.type.headingSm, color = Hf.colors.textPrimary)

            UnitToggleRow(
                label = "Height",
                options = listOf(
                    HeightUnit.FEET_INCHES to "ft / in",
                    HeightUnit.CENTIMETERS to "cm",
                ),
                selected = prefs.height,
                onSelect = viewModel::setHeight,
            )
            UnitToggleRow(
                label = "Weight",
                options = listOf(
                    WeightUnit.POUNDS to "lb",
                    WeightUnit.KILOGRAMS to "kg",
                ),
                selected = prefs.weight,
                onSelect = viewModel::setWeight,
            )
            UnitToggleRow(
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
}

@Composable
private fun <T> UnitToggleRow(
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
        Row(
            modifier = Modifier
                .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(9.dp))
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                Text(
                    text = text,
                    style = Hf.type.capsSm,
                    color = if (isSelected) Hf.colors.textInverse else Hf.colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (isSelected) Hf.colors.accent else Color.Transparent)
                        .clickable { onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}
