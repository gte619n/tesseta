package com.gte619n.healthfitness.feature.blood

import androidx.compose.runtime.getValue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddReadingScreen(
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: AddReadingViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    HfCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
        HfScreenHeader(
            title = "Add reading",
            subtitle = "Log a single blood marker",
            onBack = onBack,
        )
        Spacer(Modifier.height(12.dp))

        Text("Marker", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkerCatalog.DISPLAY_ORDER.forEach { marker ->
                FilterChip(
                    selected = form.marker == marker,
                    onClick = { viewModel.onMarker(marker) },
                    label = { Text(MarkerCatalog.displayName(marker)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = form.value,
            onValueChange = viewModel::onValue,
            label = { Text("Value") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.unit,
            onValueChange = viewModel::onUnit,
            label = { Text("Unit (optional — server default used if blank)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.labSource,
            onValueChange = viewModel::onLabSource,
            label = { Text("Lab source (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.notes,
            onValueChange = viewModel::onNotes,
            label = { Text("Notes (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Sample date: ${form.sampleDate}",
            style = Hf.type.bodySm,
            color = Hf.colors.textTertiary,
        )

        form.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(err, style = Hf.type.bodySm, color = Hf.colors.alert)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { viewModel.submit(onSuccess = onDone) },
                enabled = form.canSubmit,
                modifier = Modifier.weight(1f),
            ) {
                if (form.submitting) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                } else {
                    Text("Save")
                }
            }
        }
      }
    }
}
