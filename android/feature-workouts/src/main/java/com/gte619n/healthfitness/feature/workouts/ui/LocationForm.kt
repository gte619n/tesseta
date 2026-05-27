package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Form state shared by New/Edit gym screens.
 */
data class LocationFormState(
    val name: String = "",
    val address: String = "",
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlot?> = DayOfWeek.entries.associateWith { null },
    val amenities: Set<Amenity> = emptySet(),
    val submitting: Boolean = false,
    val error: String? = null,
)

/**
 * Stateless form composable. Owns no state; callers pass [state] and
 * receive [onChange] callbacks. Used by both NewGym and EditGym screens.
 */
@Composable
fun LocationForm(
    state: LocationFormState,
    onChange: (LocationFormState) -> Unit,
    onSubmit: () -> Unit,
    submitLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        FormSection(title = "Name") {
            OutlinedTextField(
                value = state.name,
                onValueChange = { onChange(state.copy(name = it)) },
                placeholder = { Text("My gym", style = Hf.type.bodyMd, color = Hf.colors.textQuaternary) },
                textStyle = Hf.type.bodyMd,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection(title = "Address (optional)") {
            OutlinedTextField(
                value = state.address,
                onValueChange = { onChange(state.copy(address = it)) },
                placeholder = { Text("123 Main St", style = Hf.type.bodyMd, color = Hf.colors.textQuaternary) },
                textStyle = Hf.type.bodyMd,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection(title = "Hours") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.is24Hours,
                    onCheckedChange = { onChange(state.copy(is24Hours = it)) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Hf.colors.surface,
                        checkedTrackColor = Hf.colors.accent,
                        uncheckedThumbColor = Hf.colors.surface,
                        uncheckedTrackColor = Hf.colors.borderStrong,
                    ),
                )
                Spacer(Modifier.size(12.dp))
                Text("24-hour access", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            }
            if (!state.is24Hours) {
                Spacer(Modifier.height(12.dp))
                HoursMatrix(
                    hours = state.hours,
                    onChange = { onChange(state.copy(hours = it)) },
                )
            }
        }
        FormSection(title = "Amenities") {
            AmenityChipGrid(
                selected = state.amenities,
                onToggle = { amenity ->
                    val next = state.amenities.toMutableSet().apply {
                        if (amenity in this) remove(amenity) else add(amenity)
                    }
                    onChange(state.copy(amenities = next))
                },
            )
        }
        if (state.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.error, style = Hf.type.bodySm, color = Hf.colors.alert)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = !state.submitting,
            colors = ButtonDefaults.buttonColors(
                containerColor = Hf.colors.accent,
                contentColor = Hf.colors.surface,
                disabledContainerColor = Hf.colors.canvasMuted,
                disabledContentColor = Hf.colors.textTertiary,
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (state.submitting) "Saving..." else submitLabel,
                style = Hf.type.bodyMd,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FormSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(
            title,
            style = Hf.type.capsMd,
            color = Hf.colors.textSecondary,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderSubtle),
        )
        Spacer(Modifier.height(10.dp))
        content()
    }
}
