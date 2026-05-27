package com.gte619n.healthfitness.feature.workouts.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.CoverPhotoUploader
import com.gte619n.healthfitness.feature.workouts.ui.LocationForm
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun EditGymScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    vm: EditGymViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = { Header(onBack = onBack) },
        containerColor = Hf.colors.canvas,
    ) { inner ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(inner)) {
            when {
                state.loading -> LoadingState(label = "Loading gym...")
                state.loadError != null -> ErrorState(
                    message = state.loadError!!,
                    onRetry = vm::load,
                )
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            "Cover photo".uppercase(),
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
                        CoverPhotoUploader(
                            currentUrl = state.coverPhotoUrl,
                            uploading = state.uploadingPhoto,
                            onPickImage = { uri ->
                                vm.uploadCoverPhoto(context.contentResolver, uri)
                            },
                            onDelete = vm::deleteCoverPhoto,
                        )
                    }
                    LocationForm(
                        state = state.form,
                        onChange = { vm.updateForm { _ -> it } },
                        onSubmit = { vm.submit(onSuccess = onDone) },
                        submitLabel = "Save changes",
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Hf.colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Hf.colors.textPrimary,
                )
            }
            Text("Edit gym", style = Hf.type.headingMd, color = Hf.colors.textPrimary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderDefault),
        )
    }
}
