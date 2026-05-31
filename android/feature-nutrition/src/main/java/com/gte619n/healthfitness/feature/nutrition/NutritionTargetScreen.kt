package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun NutritionTargetRoute(
    onBack: () -> Unit,
    viewModel: NutritionTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NutritionTargetScreen(state = state, onSave = viewModel::save, onBack = onBack)
}

@Composable
fun NutritionTargetScreen(
    state: NutritionTargetUiState,
    onSave: (Macros) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Hf.colors.textSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(onClick = onBack),
            )
            Text("Daily target", style = Hf.type.headingLg.copy(fontSize = 20.sp), color = Hf.colors.textPrimary)
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = Hf.colors.accent)
            }
            else -> TargetForm(state = state, onSave = onSave)
        }
    }
}

@Composable
private fun TargetForm(state: NutritionTargetUiState, onSave: (Macros) -> Unit) {
    val t = state.target
    var kcal by remember(t) { mutableStateOf(t?.caloriesKcal.toFieldText()) }
    var protein by remember(t) { mutableStateOf(t?.proteinGrams.toFieldText()) }
    var carbs by remember(t) { mutableStateOf(t?.carbsGrams.toFieldText()) }
    var fat by remember(t) { mutableStateOf(t?.fatGrams.toFieldText()) }
    var fiber by remember(t) { mutableStateOf(t?.fiberGrams.toFieldText()) }
    var sugar by remember(t) { mutableStateOf(t?.sugarGrams.toFieldText()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 24.dp),
    ) {
        Text(
            "Set the six daily macro values you want to track against.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(14.dp))
        HfCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                TargetField("Calories (kcal)", kcal) { kcal = it }
                TargetField("Protein (g)", protein) { protein = it }
                TargetField("Carbs (g)", carbs) { carbs = it }
                TargetField("Fat (g)", fat) { fat = it }
                TargetField("Fiber (g)", fiber) { fiber = it }
                TargetField("Sugar (g)", sugar) { sugar = it }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.error != null) {
            Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
            Spacer(Modifier.height(8.dp))
        }
        if (state.saved) {
            Text("Target saved.", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton(
            text = if (state.saving) "SAVING…" else "SAVE TARGET",
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!state.saving) {
                onSave(
                    Macros(
                        caloriesKcal = kcal.toDoubleOrNull(),
                        proteinGrams = protein.toDoubleOrNull(),
                        carbsGrams = carbs.toDoubleOrNull(),
                        fatGrams = fat.toDoubleOrNull(),
                        fiberGrams = fiber.toDoubleOrNull(),
                        sugarGrams = sugar.toDoubleOrNull(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun TargetField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

private fun Double?.toFieldText(): String =
    if (this == null) "" else if (this == toLong().toDouble()) toLong().toString() else toString()

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun NutritionTargetPreview() {
    HealthFitnessTheme {
        NutritionTargetScreen(
            state = NutritionTargetUiState(
                loading = false,
                target = Macros(2000.0, 150.0, 200.0, 60.0, 30.0, 50.0),
            ),
            onSave = {},
            onBack = {},
        )
    }
}
