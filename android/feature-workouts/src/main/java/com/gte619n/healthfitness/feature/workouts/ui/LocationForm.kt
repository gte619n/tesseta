package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Shared gym create/edit form: name + address, 24-hour switch,
 * [HoursMatrix], [AmenityChipGrid]. Used by both New and Edit screens.
 */
@Composable
fun LocationForm(
    state: LocationFormState,
    onChange: (LocationFormState) -> Unit,
    onSubmit: () -> Unit,
    submitLabel: String,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        header()

        OutlinedTextField(
            value = state.name,
            onValueChange = { onChange(state.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.address,
            onValueChange = { onChange(state.copy(address = it)) },
            label = { Text("Address (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("24-hour access", style = Hf.type.bodyMd, color = Hf.colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = state.is24Hours,
                onCheckedChange = { onChange(state.copy(is24Hours = it)) },
            )
        }

        if (!state.is24Hours) {
            SectionTitle("Hours")
            HoursMatrix(
                state = state.hours,
                onChange = { onChange(state.copy(hours = it)) },
            )
        }

        SectionTitle("Amenities")
        AmenityChipGrid(
            selected = state.amenities,
            onToggle = { amenity ->
                val next = if (amenity in state.amenities) state.amenities - amenity else state.amenities + amenity
                onChange(state.copy(amenities = next))
            },
        )

        if (state.error != null) {
            Text(state.error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }

        Button(
            onClick = onSubmit,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.submitting) "Saving…" else submitLabel)
        }
    }
}
