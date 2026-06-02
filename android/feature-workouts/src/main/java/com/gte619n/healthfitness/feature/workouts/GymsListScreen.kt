package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.LocationCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun GymsListScreen(
    onBack: () -> Unit,
    onAddGym: () -> Unit,
    onOpenGym: (String) -> Unit,
    vm: GymsListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).background(Hf.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = "Gyms",
                subtitle = "Your gyms and their equipment",
                onBack = onBack,
            )

            when {
                state.loading -> LoadingState(Modifier.fillMaxSize())
                state.error != null -> ErrorState(
                    message = state.error!!,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = vm::refresh,
                )
                state.locations.isEmpty() -> EmptyState(
                    title = "No gyms yet",
                    description = "Add a gym to track its equipment and hours.",
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 280.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.locations, key = { it.locationId }) { location ->
                        LocationCard(location = location, onClick = { onOpenGym(location.locationId) })
                    }
                }
            }
        }

        // FAB to add a gym.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(52.dp)
                .background(Hf.colors.accent, CircleShape)
                .clickable { onAddGym() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Add gym",
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
