package com.gte619n.healthfitness.feature.bodycomposition.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.feature.bodycomposition.components.DexaRegionGrid
import com.gte619n.healthfitness.feature.bodycomposition.components.EditableNumberCell
import com.gte619n.healthfitness.feature.bodycomposition.components.HfCard
import com.gte619n.healthfitness.feature.bodycomposition.components.SectionTitle
import com.gte619n.healthfitness.ui.snackbar.LocalSnackbarController
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DEXA scan detail. Renders the whole-body summary, regional grid, bone
 * + metabolism stats, and the action row (view PDF, delete). Every
 * numeric value is an [EditableNumberCell] wired to optimistic PATCH +
 * revert in the ViewModel.
 */
@Composable
fun DexaScanDetailScreen(
    onBack: () -> Unit,
    vm: DexaScanDetailViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pdfStatus by vm.pdfStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbarController.current
    var showConfirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(state.transientMessage) {
        val msg = state.transientMessage
        if (msg != null) {
            snackbar.show(msg)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(pdfStatus) {
        val s = pdfStatus
        if (s is DexaScanDetailViewModel.PdfStatus.Error) {
            snackbar.show(s.message, isError = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        TopBar(onBack = onBack)
        when {
            state.loading && state.scan == null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Hf.colors.accent)
                }
            }
            state.error != null && state.scan == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.error!!,
                        style = Hf.type.bodyMd,
                        color = Hf.colors.alert,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = vm::load) {
                        Text("Try again", color = Hf.colors.accent)
                    }
                }
            }
            state.scan != null -> {
                val scan = state.scan!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetaCard(scan = scan)
                    WholeBodyCard(scan = scan, onPatch = vm::patchField)
                    DexaRegionGrid(scan = scan, onPatch = vm::patchField)
                    BoneAndMetabolismCard(scan = scan, onPatch = vm::patchField)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = { vm.openPdf { intent -> context.startActivity(intent) } },
                            modifier = Modifier.weight(1f),
                        ) {
                            if (pdfStatus is DexaScanDetailViewModel.PdfStatus.Downloading) {
                                CircularProgressIndicator(
                                    color = Hf.colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text("Loading PDF…")
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.PictureAsPdf,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text("View PDF")
                            }
                        }
                        Button(
                            onClick = { showConfirmDelete = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Hf.colors.alertBg,
                                contentColor = Hf.colors.alert,
                            ),
                            modifier = Modifier.weight(1f),
                            enabled = !state.deleting,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.padding(horizontal = 4.dp))
                            Text(if (state.deleting) "Deleting…" else "Delete scan")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title = { Text("Delete this DEXA scan?") },
            text = { Text("The scan and its PDF will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmDelete = false
                    vm.delete(onDone = onBack)
                }) {
                    Text("Delete", color = Hf.colors.alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Hf.colors.textPrimary,
            )
        }
        Text(
            text = "DEXA scan",
            style = Hf.type.headingMd,
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetaCard(scan: DexaScan) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = scan.measuredOn?.let { DATE.format(it) } ?: "Unknown date",
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
            val facility = scan.sourceFacility
            if (!facility.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = facility,
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun WholeBodyCard(scan: DexaScan, onPatch: (String, Double?) -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionTitle("Whole body")
            Spacer(Modifier.height(10.dp))
            EditableRow(label = "Total mass", value = scan.totalMassLb, unit = "lb",
                onSave = { onPatch("totalMassLb", it) })
            EditableRow(label = "Lean tissue", value = scan.leanTissueLb, unit = "lb",
                onSave = { onPatch("leanTissueLb", it) })
            EditableRow(label = "Fat tissue", value = scan.fatTissueLb, unit = "lb",
                onSave = { onPatch("fatTissueLb", it) })
            EditableRow(label = "Body fat", value = scan.totalBodyFatPercent, unit = "%",
                onSave = { onPatch("totalBodyFatPercent", it) })
            EditableRow(label = "Visceral fat", value = scan.visceralFatLb, unit = "lb",
                onSave = { onPatch("visceralFatLb", it) })
            EditableRow(label = "Android/gynoid", value = scan.androidGynoidRatio, unit = "",
                onSave = { onPatch("androidGynoidRatio", it) }, decimals = 2)
        }
    }
}

@Composable
private fun BoneAndMetabolismCard(scan: DexaScan, onPatch: (String, Double?) -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            SectionTitle("Bone density & metabolism")
            Spacer(Modifier.height(10.dp))
            EditableRow(label = "BMD T-score", value = scan.bmdTScore, unit = "",
                onSave = { onPatch("bmdTScore", it) }, decimals = 2)
            EditableRow(label = "BMD Z-score", value = scan.bmdZScore, unit = "",
                onSave = { onPatch("bmdZScore", it) }, decimals = 2)
            EditableRow(
                label = "Resting metabolic rate",
                value = scan.restingMetabolicRateKcal?.toDouble(),
                unit = "kcal/day",
                onSave = { onPatch("restingMetabolicRateKcal", it) },
                decimals = 0,
            )
        }
    }
}

@Composable
private fun EditableRow(
    label: String,
    value: Double?,
    unit: String,
    onSave: (Double?) -> Unit,
    decimals: Int = 1,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Hf.type.bodyMd.copy(fontSize = 12.sp),
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        EditableNumberCell(
            value = value,
            onSave = onSave,
            decimals = decimals,
            suffix = unit.ifBlank { null },
        )
    }
}

private val DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
