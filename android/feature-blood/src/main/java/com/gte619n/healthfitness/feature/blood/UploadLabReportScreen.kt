package com.gte619n.healthfitness.feature.blood

import androidx.compose.runtime.getValue

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.blood.components.UploadPhaseStepper
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.sync.OfflineNotice
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun UploadLabReportScreen(
    onComplete: (reportId: String) -> Unit,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: UploadLabReportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val online by viewModel.isOnline.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        val name = context.contentResolver.queryDisplayName(uri) ?: "report.pdf"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        viewModel.upload(name, bytes)
    }

    // D17: the PDF extraction is an online-only AI flow — only open the picker
    // when online; offline shows a "needs connection" affordance and queues nothing.
    LaunchedEffect(online) { if (online) picker.launch("application/pdf") }

    val ui by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(ui) {
        (ui as? UploadLabReportViewModel.UiState.Complete)?.let { onComplete(it.report.reportId) }
    }

    if (!online) {
        HfCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                HfScreenHeader(
                    title = "Upload lab PDF",
                    subtitle = "Extract markers from a report",
                    onBack = onBack,
                )
                Spacer(Modifier.height(12.dp))
                OfflineNotice(
                    message = "Reading a lab PDF uses AI and needs an internet connection.",
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
        return
    }

    UploadLabReportSheet(state = ui, onBack = onBack, onDismiss = { viewModel.cancel(); onDismiss() })
}

@Composable
private fun UploadLabReportSheet(
    state: UploadLabReportViewModel.UiState,
    onBack: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    HfCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        HfScreenHeader(
            title = "Upload lab PDF",
            subtitle = "Extract markers from a report",
            onBack = onBack,
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
            UploadLabReportViewModel.UiState.Idle ->
                Text("Choose a PDF to begin.", style = Hf.type.bodyMd, color = Hf.colors.textSecondary)

            is UploadLabReportViewModel.UiState.Failed -> {
                Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
            }

            is UploadLabReportViewModel.UiState.Complete -> {
                Text("Extraction complete.", style = Hf.type.bodyMd, color = Hf.colors.good)
            }

            else -> UploadPhaseStepper(state = state)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDismiss) {
                Text(if (state is UploadLabReportViewModel.UiState.Failed) "Close" else "Cancel")
            }
        }
      }
    }
}

internal fun ContentResolver.queryDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
