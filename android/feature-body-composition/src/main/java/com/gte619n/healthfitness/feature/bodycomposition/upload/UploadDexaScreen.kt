package com.gte619n.healthfitness.feature.bodycomposition.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.sync.OfflineGate
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun UploadDexaScreen(navController: NavHostController) {
    val vm: UploadDexaViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val online by vm.isOnline.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val resolver = context.contentResolver
            val name = uri.lastPathSegment ?: "dexa.pdf"
            val bytes = runCatching {
                resolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                vm.upload(name, bytes)
            }
        }
    }

    // On successful completion, route to the new scan's detail screen.
    LaunchedEffect(state) {
        val s = state
        if (s is UploadDexaViewModel.UiState.Complete) {
            navController.navigate(BodyCompositionRoutes.scanDetail(s.scanId)) {
                popUpTo(BodyCompositionRoutes.BODY)
            }
            vm.reset()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Upload DEXA scan",
            subtitle = "Import a DEXA report PDF",
            onBack = { navController.popBackStack() },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
            is UploadDexaViewModel.UiState.Idle -> {
                // D17 (#41): online-only AI extraction — gate the picker offline.
                OfflineGate(
                    online = online,
                    message = "Importing a DEXA PDF uses AI and needs an internet connection.",
                ) {
                    Text(
                        text = "Pick a DEXA report PDF (max 25 MB).",
                        style = Hf.type.bodyMd,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Hf.colors.accent, RoundedCornerShape(10.dp))
                            .clickable { picker.launch("application/pdf") }
                            .padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, tint = Hf.colors.textInverse, modifier = Modifier.size(16.dp))
                        Text("  CHOOSE PDF", style = Hf.type.capsSm, color = Hf.colors.textInverse)
                    }
                }
            }

            is UploadDexaViewModel.UiState.InProgress -> {
                CircularProgressIndicator()
                Text(text = phaseLabel(s.phase), style = Hf.type.headingSm)
                s.message?.let { Text(text = it, style = Hf.type.bodySm) }
            }

            is UploadDexaViewModel.UiState.Complete -> {
                Text(text = "Upload complete.", style = Hf.type.headingSm)
            }

            is UploadDexaViewModel.UiState.Failed -> {
                Text(
                    text = s.error,
                    style = Hf.type.bodyMd,
                    color = Hf.colors.alert,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Hf.colors.accent, RoundedCornerShape(10.dp))
                        .clickable {
                            vm.reset()
                            picker.launch("application/pdf")
                        }
                        .padding(vertical = 13.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = Hf.colors.textInverse, modifier = Modifier.size(16.dp))
                    Text("  TRY AGAIN", style = Hf.type.capsSm, color = Hf.colors.textInverse)
                }
            }
        }
        }
    }
}

private fun phaseLabel(phase: String): String = when (phase) {
    "uploading" -> "Uploading"
    "extracting" -> "Extracting"
    "saving" -> "Saving"
    else -> phase.replaceFirstChar { it.uppercase() }
}
