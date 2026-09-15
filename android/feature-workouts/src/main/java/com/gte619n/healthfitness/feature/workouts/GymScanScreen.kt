package com.gte619n.healthfitness.feature.workouts

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.data.workouts.ImportPreviewItemDto
import com.gte619n.healthfitness.feature.workouts.GymScanViewModel.Stage
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * IMPL-GYM-003: pick a walkthrough video, upload it, and review the detected
 * equipment before adding it to the gym. Reuses the backend's preview/confirm
 * flow; the review UI mirrors the web modal.
 */
@Composable
fun GymScanScreen(
    onBack: () -> Unit,
    vm: GymScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "video/mp4"
        var size = 0L
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) size = c.getLong(idx)
            }
        }
        vm.analyzeVideo(mime, size) { resolver.openInputStream(uri) ?: error("Cannot open video") }
    }

    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = "Scan gym",
                subtitle = "Detect equipment from a walkthrough video",
                onBack = onBack,
            )
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                when (state.stage) {
                    Stage.IDLE -> IdleView(
                        error = state.error,
                        onPick = { picker.launch("video/*") },
                    )
                    Stage.UPLOADING -> Progress("Uploading video…")
                    Stage.ANALYZING -> Progress("Watching the video and detecting equipment…")
                    Stage.CONFIRMING -> Progress("Adding equipment…")
                    Stage.REVIEW -> ReviewView(vm, state)
                    Stage.DONE -> DoneView(state, onBack)
                }
            }
        }
    }
}

@Composable
private fun IdleView(error: String?, onPick: () -> Unit) {
    Text(
        "Upload a slow, steady walkthrough of the gym with good lighting. "
            + "We'll detect the equipment for you to review. MP4 or MOV, up to 200 MB.",
        style = Hf.type.bodyMd,
        color = Hf.colors.textSecondary,
    )
    Spacer(Modifier.size(16.dp))
    Button(onClick = onPick) { Text("Choose a video") }
    if (error != null) {
        Spacer(Modifier.size(12.dp))
        Text(error, style = Hf.type.bodySm, color = Hf.colors.alert)
    }
}

@Composable
private fun Progress(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.size(16.dp))
        Text(message, style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
    }
}

@Composable
private fun ReviewView(vm: GymScanViewModel, state: GymScanViewModel.UiState) {
    val preview = state.preview ?: return
    Text("Review equipment", style = Hf.type.headingSm, color = Hf.colors.textPrimary)
    Spacer(Modifier.size(4.dp))
    Text(
        "${preview.summary.total} detected · ${preview.summary.matched} matched · "
            + "${preview.summary.newSubmissions} new",
        style = Hf.type.bodySm,
        color = Hf.colors.muted,
    )
    Spacer(Modifier.size(12.dp))
    preview.items.forEach { item ->
        ReviewRow(
            item = item,
            action = state.rows[item.index]?.action ?: "CREATE_NEW",
            onAction = { vm.setRowAction(item.index, it) },
        )
        Spacer(Modifier.size(8.dp))
    }
    if (state.error != null) {
        Text(state.error, style = Hf.type.bodySm, color = Hf.colors.alert)
        Spacer(Modifier.size(8.dp))
    }
    val addable = preview.items.count { (state.rows[it.index]?.action ?: "CREATE_NEW") != "SKIP" }
    Button(onClick = { vm.confirm() }, enabled = addable > 0, modifier = Modifier.fillMaxWidth()) {
        Text("Add $addable item${if (addable == 1) "" else "s"}")
    }
    Spacer(Modifier.size(24.dp))
}

@Composable
private fun ReviewRow(
    item: ImportPreviewItemDto,
    action: String,
    onAction: (String) -> Unit,
) {
    Column {
        Text(item.parsed.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        val sub = listOfNotNull(
            item.parsed.category.takeIf { it.isNotBlank() },
            item.parsed.confidence?.takeIf { it != "CERTAIN" },
        ).joinToString(" · ")
        if (sub.isNotBlank()) {
            Text(sub, style = Hf.type.bodySm, color = Hf.colors.muted)
        }
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item.match != null) {
                FilterChip(
                    selected = action == "USE_MATCH",
                    onClick = { onAction("USE_MATCH") },
                    label = { Text("Use match") },
                )
            }
            FilterChip(
                selected = action == "CREATE_NEW",
                onClick = { onAction("CREATE_NEW") },
                label = { Text("New") },
            )
            FilterChip(
                selected = action == "SKIP",
                onClick = { onAction("SKIP") },
                label = { Text("Skip") },
            )
        }
    }
}

@Composable
private fun DoneView(state: GymScanViewModel.UiState, onBack: () -> Unit) {
    val result = state.result
    Text("Equipment added", style = Hf.type.headingSm, color = Hf.colors.textPrimary)
    Spacer(Modifier.size(8.dp))
    if (result != null) {
        Text(
            "Added ${result.addedToLocation} · ${result.created.size} new "
                + "submission${if (result.created.size == 1) "" else "s"} · skipped ${result.skipped}",
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
    }
    Spacer(Modifier.size(24.dp))
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}
