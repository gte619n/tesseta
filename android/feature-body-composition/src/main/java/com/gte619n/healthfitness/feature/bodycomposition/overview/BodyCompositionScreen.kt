package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.bodycomposition.components.BodyCompositionHero
import com.gte619n.healthfitness.feature.bodycomposition.components.DexaScanCard
import com.gte619n.healthfitness.feature.bodycomposition.components.HfCard
import com.gte619n.healthfitness.feature.bodycomposition.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Body composition overview. Shows the hero card (weight, body-fat %,
 * lean mass, 7d/90d deltas, 90-day weight chart) followed by a vertical
 * list of DEXA scan cards. The "Upload DEXA scan" affordance lives in a
 * top-bar action.
 */
@Composable
fun BodyCompositionScreen(
    onBack: () -> Unit,
    onScanClick: (scanId: String) -> Unit,
    onUploadClick: () -> Unit,
    vm: BodyCompositionViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        TopBar(
            onBack = onBack,
            onRefresh = vm::refresh,
            onUpload = onUploadClick,
            loading = state.loading,
        )
        if (state.error != null && state.snapshot == null) {
            ErrorState(message = state.error!!, onRetry = vm::refresh)
            return@Column
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val snap = state.snapshot
                if (snap != null) {
                    BodyCompositionHero(snapshot = snap)
                } else if (state.loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = Hf.colors.accent) }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("DEXA scans")
                    if (state.dexaScans.isNotEmpty()) {
                        Text(
                            text = "${state.dexaScans.size}",
                            style = Hf.type.bodySm.copy(fontSize = 11.sp),
                            color = Hf.colors.textTertiary,
                        )
                    }
                }
            }
            if (state.dexaScans.isEmpty() && !state.loading) {
                item {
                    HfCard {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "No DEXA scans yet",
                                style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                                color = Hf.colors.textPrimary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Tap the upload icon to add your first DEXA report PDF.",
                                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                                color = Hf.colors.textTertiary,
                            )
                        }
                    }
                }
            } else {
                items(state.dexaScans, key = { it.scanId }) { summary ->
                    DexaScanCard(summary = summary, onClick = { onScanClick(summary.scanId) })
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.Outlined.ArrowBack,
                contentDescription = "Back",
                tint = Hf.colors.textPrimary,
            )
        }
        Text(
            text = "Body composition",
            style = Hf.type.headingMd,
            color = Hf.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(
                color = Hf.colors.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.padding(horizontal = 8.dp))
        }
        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "Refresh",
                tint = Hf.colors.textPrimary,
            )
        }
        IconButton(onClick = onUpload) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Upload DEXA scan",
                tint = Hf.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Could not load body composition",
            style = Hf.type.bodyMd,
            color = Hf.colors.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = Hf.type.bodySm.copy(fontSize = 11.sp),
            color = Hf.colors.textTertiary,
        )
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = onRetry) {
            Text(
                text = "Try again",
                color = Hf.colors.accent,
            )
        }
    }
}
