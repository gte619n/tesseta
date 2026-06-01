package com.gte619n.healthfitness.feature.bodycomposition.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun DexaScanDetailScreen(navController: NavHostController) {
    val vm: DexaScanDetailViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "DEXA scan",
            subtitle = "Body composition breakdown",
            onBack = { navController.popBackStack() },
        )

        when {
            state.loading && state.scan == null ->
                LoadingState(modifier = Modifier.fillMaxSize())

            state.error != null && state.scan == null ->
                ErrorState(message = state.error!!, onRetry = { vm.load() })

            else -> {
                val scan = state.scan ?: return
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HfCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    TotalsRow("Total mass", scan.totalMassLb, weightUnit = weightUnit) {
                        vm.patchField("totalMassLb", it)
                    }
                    TotalsRow("Lean tissue", scan.leanTissueLb, weightUnit = weightUnit) {
                        vm.patchField("leanTissueLb", it)
                    }
                    TotalsRow("Fat tissue", scan.fatTissueLb, weightUnit = weightUnit) {
                        vm.patchField("fatTissueLb", it)
                    }
                    TotalsRow("Body fat %", scan.totalBodyFatPercent, unit = "%") {
                        vm.patchField("totalBodyFatPercent", it)
                    }
                    TotalsRow("Visceral fat", scan.visceralFatLb, weightUnit = weightUnit) {
                        vm.patchField("visceralFatLb", it)
                    }
                    TotalsRow("A/G ratio", scan.androidGynoidRatio, unit = "") {
                        vm.patchField("androidGynoidRatio", it)
                    }
                    TotalsRow("BMD T-score", scan.bmdTScore, unit = "") {
                        vm.patchField("bmdTScore", it)
                    }
                    TotalsRow("BMD Z-score", scan.bmdZScore, unit = "") {
                        vm.patchField("bmdZScore", it)
                    }
                  }
                }

                SectionTitle("Regions")
                DexaRegionGrid(
                    scan = scan,
                    onPatch = { path, value -> vm.patchField(path, value) },
                    weightUnit = weightUnit,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { vm.viewPdf(context) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("View PDF")
                    }
                    Button(
                        onClick = { showDeleteConfirm = true },
                        enabled = !state.deleting,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Delete scan")
                    }
                }
            }

            if (showDeleteConfirm) {
                ConfirmDialog(
                    title = "Delete scan",
                    message = "This permanently removes the DEXA scan and its PDF.",
                    confirmLabel = "Delete",
                    dismissLabel = "Cancel",
                    onConfirm = {
                        showDeleteConfirm = false
                        vm.delete(onDone = { navController.popBackStack() })
                    },
                    onDismiss = { showDeleteConfirm = false },
                )
            }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun TotalsRow(
    label: String,
    value: Double?,
    unit: String? = null,
    weightUnit: WeightUnit? = null,
    onSave: (Double?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = Hf.type.bodyMd)
        }
        EditableNumberCell(
            value = value,
            unit = unit?.ifBlank { null },
            weightUnit = weightUnit,
            onSave = onSave,
        )
    }
}
