package com.gte619n.healthfitness.feature.bodycomposition.upload

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.bodycomposition.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Dialog-shape upload sheet. Launches the OS file picker filtered to
 * `application/pdf`, streams the bytes to the ViewModel, and surfaces
 * the SSE phase ladder. On `Complete`, fires [onComplete] with the new
 * scanId so the caller can navigate to the detail screen.
 */
@Composable
fun UploadDexaScreen(
    onComplete: (scanId: String) -> Unit,
    onDismiss: () -> Unit,
    vm: UploadDexaViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val ui by vm.state.collectAsStateWithLifecycle()
    val pickedAtLeastOnce = remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        pickedAtLeastOnce.value = true
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        val name = uri.queryDisplayName(context) ?: "dexa.pdf"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        vm.upload(name, bytes)
    }

    LaunchedEffect(Unit) {
        if (!pickedAtLeastOnce.value) picker.launch("application/pdf")
    }
    LaunchedEffect(ui) {
        val state = ui
        if (state is UploadDexaViewModel.UiState.Complete) {
            onComplete(state.scan.scanId)
        }
    }

    HfCard(modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Upload DEXA scan",
                style = Hf.type.headingMd,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            PhaseSteps(ui = ui)
            Spacer(Modifier.height(14.dp))
            val message = when (val s = ui) {
                is UploadDexaViewModel.UiState.Idle -> "Choose a DEXA PDF to upload"
                is UploadDexaViewModel.UiState.InProgress ->
                    s.message ?: phaseMessage(s.phase)
                is UploadDexaViewModel.UiState.Complete -> "Done — opening scan"
                is UploadDexaViewModel.UiState.Failed -> "Upload failed: ${s.error}"
            }
            Text(
                text = message,
                style = Hf.type.bodySm.copy(fontSize = 12.sp),
                color = if (ui is UploadDexaViewModel.UiState.Failed) Hf.colors.alert
                else Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    vm.cancel()
                    onDismiss()
                }) {
                    Text("Cancel")
                }
                if (ui is UploadDexaViewModel.UiState.Failed) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            pickedAtLeastOnce.value = false
                            picker.launch("application/pdf")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Hf.colors.accent),
                    ) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseSteps(ui: UploadDexaViewModel.UiState) {
    val current = when (ui) {
        is UploadDexaViewModel.UiState.InProgress -> ui.phase
        is UploadDexaViewModel.UiState.Complete -> "complete"
        is UploadDexaViewModel.UiState.Failed -> "uploading"
        is UploadDexaViewModel.UiState.Idle -> "uploading"
    }
    val phases = listOf("uploading", "extracting", "saving")
    val currentIdx = phases.indexOf(current).let { if (it < 0) phases.size else it }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        phases.forEachIndexed { i, name ->
            val tone = when {
                ui is UploadDexaViewModel.UiState.Failed -> Hf.colors.alert
                i < currentIdx -> Hf.colors.good
                i == currentIdx && ui !is UploadDexaViewModel.UiState.Complete -> Hf.colors.accent
                else -> Hf.colors.borderDefault
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(tone, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
            )
        }
    }
}

private fun phaseMessage(phase: String): String = when (phase) {
    "uploading" -> "Uploading PDF…"
    "extracting" -> "Reading the scan with Gemini…"
    "saving" -> "Storing the results…"
    "complete" -> "Done"
    else -> phase
}

private fun Uri.queryDisplayName(context: android.content.Context): String? {
    val cursor = context.contentResolver.query(this, null, null, null, null) ?: return null
    cursor.use { c ->
        if (!c.moveToFirst()) return null
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx < 0) return null
        return c.getString(idx)
    }
}

