package com.gte619n.healthfitness.feature.workouts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.LocationCard
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GymsListScreen(
    onBack: () -> Unit,
    onAddGym: () -> Unit,
    onOpenGym: (String) -> Unit,
    vm: GymsListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workouts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGym) {
                Icon(Icons.Filled.Add, contentDescription = "Add gym")
            }
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize().padding(padding))
            state.error != null -> ErrorState(
                message = state.error!!,
                modifier = Modifier.fillMaxSize().padding(padding),
                onRetry = vm::refresh,
            )
            state.locations.isEmpty() -> EmptyState(
                title = "No gyms yet",
                description = "Add a gym to track its equipment and hours.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
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
}
