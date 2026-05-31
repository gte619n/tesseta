package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun BodyCompositionScreen(
    navController: NavController,
    vm: BodyCompositionViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val weightUnit by vm.weightUnit.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Body composition",
            subtitle = "Weight trend and DEXA scans",
            onBack = { navController.popBackStack() },
        )
        when {
            s.loading && s.snapshot == null -> LoadingState()
            s.error != null && s.snapshot == null -> ErrorState(
                message = s.error!!,
                onRetry = vm::refresh,
            )
            else -> BodyCompositionContent(
                state = s,
                navController = navController,
                weightUnit = weightUnit,
            )
        }
    }
}

@Composable
private fun BodyCompositionContent(
    state: BodyCompositionViewModel.UiState,
    navController: NavController,
    weightUnit: WeightUnit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        state.snapshot?.let { snap ->
            BodyCompositionHero(snapshot = snap, weightUnit = weightUnit)
            Spacer(Modifier.height(16.dp))
            WeightTrendChart(
                weightSeries = snap.series90d,
                bodyFatSeries = snap.series90dBodyFat,
                weightUnit = weightUnit,
            )
            Spacer(Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Hf.colors.accent, RoundedCornerShape(10.dp))
                .clickable { navController.navigate(BodyCompositionRoutes.UPLOAD) }
                .padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = Hf.colors.textInverse, modifier = Modifier.size(16.dp))
            Text("  UPLOAD DEXA SCAN", style = Hf.type.capsSm, color = Hf.colors.textInverse)
        }
        Spacer(Modifier.height(16.dp))

        if (state.dexaScans.isEmpty()) {
            EmptyState(
                title = "No DEXA scans yet",
                description = "Upload a DEXA PDF to track regional body composition.",
            )
        } else {
            Text(
                text = "DEXA SCANS",
                style = Hf.type.capsMd,
                color = Hf.colors.textTertiary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 2000.dp)
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.dexaScans, key = { it.scanId }) { scan ->
                    DexaScanCard(
                        summary = scan,
                        onClick = { navController.navigate(BodyCompositionRoutes.scanDetail(scan.scanId)) },
                        weightUnit = weightUnit,
                    )
                }
            }
        }
    }
}
