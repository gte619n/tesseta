package com.gte619n.healthfitness.feature.workouts.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.ui.LocationCard
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Phone gym list screen. The empty-state CTA shares the FAB's action
 * (create a gym) so users without any gyms don't have to hunt for the
 * floating button.
 */
@Composable
fun GymsListScreen(
    onAddGym: () -> Unit,
    onOpenGym: (String) -> Unit,
    vm: GymsListViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { GymsListHeader() },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddGym,
                containerColor = Hf.colors.accent,
                contentColor = Hf.colors.surface,
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add gym")
            }
        },
        containerColor = Hf.colors.canvas,
    ) { inner ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(inner)) {
            when {
                state.loading -> LoadingState(label = "Loading gyms...")
                state.error != null -> ErrorState(
                    message = state.error!!,
                    onRetry = vm::refresh,
                )
                state.locations.isEmpty() -> EmptyState(
                    title = "No gyms yet",
                    description = "Add your home, office, or favorite gym to start tracking workouts.",
                    icon = Icons.Outlined.FitnessCenter,
                    action = {
                        Button(
                            onClick = onAddGym,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Hf.colors.accent,
                                contentColor = Hf.colors.surface,
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Add gym", style = Hf.type.bodyMd)
                        }
                    },
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.locations, key = { it.locationId }) { loc ->
                        LocationCard(
                            location = loc,
                            onClick = { onOpenGym(loc.locationId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GymsListHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Gyms",
                style = Hf.type.headingLg,
                color = Hf.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Hf.colors.borderDefault),
        )
    }
}
