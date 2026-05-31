package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.CoverPhotoUploader
import com.gte619n.healthfitness.feature.workouts.ui.LocationForm
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun EditGymScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: EditGymViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val coverUrl by vm.coverPhotoUrl.collectAsStateWithLifecycle()
    val uploading by vm.uploading.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).background(Hf.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = "Edit gym",
                subtitle = "Update this gym's details",
                onBack = onBack,
            )
            LocationForm(
                state = form,
                onChange = vm::update,
                onSubmit = { vm.submit(onSaved) },
                submitLabel = "Save changes",
                modifier = Modifier.fillMaxSize(),
                header = {
                    CoverPhotoUploader(
                        currentUrl = coverUrl,
                        onPick = vm::uploadCoverPhoto,
                        onDelete = vm::deleteCoverPhoto,
                        uploading = uploading,
                    )
                },
            )
        }
    }
}
