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
import com.gte619n.healthfitness.feature.workouts.ui.LocationForm
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun NewGymScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
    vm: NewGymViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).background(Hf.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = "New gym",
                subtitle = "Add a gym to track equipment and hours",
                onBack = onBack,
            )
            LocationForm(
                state = form,
                onChange = vm::update,
                onSubmit = { vm.submit(onCreated) },
                submitLabel = "Create gym",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
