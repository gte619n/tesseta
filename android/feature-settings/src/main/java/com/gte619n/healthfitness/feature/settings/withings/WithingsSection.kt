package com.gte619n.healthfitness.feature.settings.withings

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.settings.connections.ConnectionCard
import com.gte619n.healthfitness.feature.settings.connections.ConnectionCardState

@Composable
fun WithingsSection(
    viewModel: WithingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Each authorize URL opens the browser exactly once (Channel-backed flow).
    // The redirect (healthfitness://withings-callback) comes back through the
    // Activity's onNewIntent → WithingsOAuthCoordinator → the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.authorizeRequests.collect { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    val cardState = when (val s = state) {
        is WithingsViewModel.UiState.Loading -> ConnectionCardState.Loading
        is WithingsViewModel.UiState.Disconnected ->
            ConnectionCardState.Disconnected(
                connecting = s.connecting,
                description = "Sync sleep from your Sleep Analyzer pad, plus weight and " +
                    "body fat from your Withings scale.",
            )
        is WithingsViewModel.UiState.Connected ->
            ConnectionCardState.Connected(
                connectedAtEpochSeconds = s.connectedAtEpochSeconds,
                disconnecting = s.disconnecting,
            )
        is WithingsViewModel.UiState.NeedsReconnect ->
            ConnectionCardState.NeedsReconnect(reconnecting = s.reconnecting)
        is WithingsViewModel.UiState.Error -> ConnectionCardState.Error(s.message)
    }

    ConnectionCard(
        title = "Withings",
        state = cardState,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
    )
}
