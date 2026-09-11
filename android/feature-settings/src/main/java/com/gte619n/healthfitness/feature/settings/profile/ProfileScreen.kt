package com.gte619n.healthfitness.feature.settings.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.prefs.HeightUnit
import com.gte619n.healthfitness.domain.profile.HeightMetric
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SegmentedChoice
import com.gte619n.healthfitness.ui.components.SettingsCard
import com.gte619n.healthfitness.ui.components.SettingsContentMaxWidth
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun ProfileScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(title = "Profile", subtitle = "Your personal details", onBack = onNavigateBack)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val s = state) {
                    is ProfileViewModel.UiState.Loading -> LoadingState()
                    is ProfileViewModel.UiState.Error -> ErrorState(
                        message = s.message,
                        onRetry = viewModel::refresh,
                    )
                    is ProfileViewModel.UiState.Loaded -> {
                        val heightUnit by viewModel.heightUnit.collectAsStateWithLifecycle()
                        ProfileLoaded(
                            profile = s.profile,
                            saving = s.saving,
                            heightUnit = heightUnit,
                            onSaveFtIn = viewModel::saveHeight,
                            onSaveCm = viewModel::saveHeightCm,
                            onSaveBiologicalSex = viewModel::saveBiologicalSex,
                            onSaveDateOfBirth = viewModel::saveDateOfBirth,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileLoaded(
    profile: Profile,
    saving: Boolean,
    heightUnit: HeightUnit,
    onSaveFtIn: (feet: Int, inches: Int) -> Unit,
    onSaveCm: (cm: Int) -> Unit,
    onSaveBiologicalSex: (String) -> Unit,
    onSaveDateOfBirth: (String) -> Unit,
) {
    SettingsCard(title = "Account") {
        ReadOnlyRow("Name", profile.displayName ?: "—", Icons.Outlined.Person)
        ReadOnlyRow("Email", profile.email ?: "—", Icons.Outlined.Email)
    }

    SettingsCard(title = "Body") {
        FieldLabel("Height", Icons.Outlined.Height)
        when (heightUnit) {
            HeightUnit.FEET_INCHES -> FeetInchesEditor(profile.heightCm, saving, onSaveFtIn)
            HeightUnit.CENTIMETERS -> CentimetersEditor(profile.heightCm, saving, onSaveCm)
        }

        FieldLabel("Biological sex", Icons.Outlined.Person)
        SegmentedChoice(
            options = listOf("MALE" to "Male", "FEMALE" to "Female"),
            selected = profile.biologicalSex,
            onSelect = onSaveBiologicalSex,
            enabled = !saving,
        )

        FieldLabel("Date of birth", Icons.Outlined.Cake)
        DateOfBirthEditor(profile.dateOfBirth, saving, onSaveDateOfBirth)
    }
}

@Composable
private fun FieldLabel(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Hf.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
    }
}

/** Date field opening a Material3 date picker; stores/sends ISO "YYYY-MM-DD". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateOfBirthEditor(
    dateOfBirth: String?,
    saving: Boolean,
    onSave: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = dateOfBirth ?: "",
        onValueChange = {},
        readOnly = true,
        enabled = !saving,
        label = { Text("YYYY-MM-DD") },
        placeholder = { Text("Pick a date") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !saving) { showPicker = true },
    )

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = isoToEpochMillis(dateOfBirth),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onSave(epochMillisToIso(it)) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private val isoDateFormat: SimpleDateFormat
    get() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

private fun isoToEpochMillis(iso: String?): Long? =
    iso?.let { runCatching { isoDateFormat.parse(it)?.time }.getOrNull() }

private fun epochMillisToIso(millis: Long): String = isoDateFormat.format(Date(millis))

@Composable
private fun FeetInchesEditor(
    heightCm: Int?,
    saving: Boolean,
    onSave: (feet: Int, inches: Int) -> Unit,
) {
    val ftIn = remember(heightCm) { HeightMetric.cmToFtIn(heightCm) }
    var feet by rememberSaveable(heightCm) { mutableStateOf(ftIn?.feet?.toString() ?: "") }
    var inches by rememberSaveable(heightCm) { mutableStateOf(ftIn?.inches?.toString() ?: "") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = feet,
            onValueChange = { feet = it.filter(Char::isDigit) },
            label = { Text("ft") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.width(96.dp),
        )
        OutlinedTextField(
            value = inches,
            onValueChange = { inches = it.filter(Char::isDigit) },
            label = { Text("in") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.width(96.dp),
        )
    }
    SaveButton(saving) { onSave(feet.toIntOrNull() ?: 0, inches.toIntOrNull() ?: 0) }
}

@Composable
private fun CentimetersEditor(
    heightCm: Int?,
    saving: Boolean,
    onSave: (cm: Int) -> Unit,
) {
    var cm by rememberSaveable(heightCm) { mutableStateOf(heightCm?.toString() ?: "") }

    OutlinedTextField(
        value = cm,
        onValueChange = { cm = it.filter(Char::isDigit) },
        label = { Text("cm") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.width(120.dp),
    )
    SaveButton(saving) { onSave(cm.toIntOrNull() ?: 0) }
}

@Composable
private fun SaveButton(saving: Boolean, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick, enabled = !saving) {
            Text(if (saving) "Saving…" else "Save")
        }
    }
}

@Composable
private fun ReadOnlyRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        }
        Text(value, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
    }
}
