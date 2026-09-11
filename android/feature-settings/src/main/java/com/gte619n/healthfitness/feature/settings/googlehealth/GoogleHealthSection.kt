package com.gte619n.healthfitness.feature.settings.googlehealth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.settings.connections.ConnectionCard
import com.gte619n.healthfitness.feature.settings.connections.ConnectionCardState

@Composable
fun GoogleHealthSection(
    viewModel: GoogleHealthViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The screen owns the launcher for the consent PendingIntent. The result
    // is fed back to the ViewModel, which parses the server auth code.
    val launcher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        viewModel.onConsentResult(result.data)
    }

    // Each emitted IntentSender is launched exactly once (Channel-backed flow).
    LaunchedEffect(viewModel) {
        viewModel.consentRequests.collect { sender ->
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    val cardState = when (val s = state) {
        is GoogleHealthViewModel.UiState.Loading -> ConnectionCardState.Loading
        is GoogleHealthViewModel.UiState.Disconnected ->
            ConnectionCardState.Disconnected(connecting = s.connecting)
        is GoogleHealthViewModel.UiState.Connected ->
            ConnectionCardState.Connected(
                connectedAtEpochSeconds = s.connectedAtEpochSeconds,
                disconnecting = s.disconnecting,
            )
        is GoogleHealthViewModel.UiState.NeedsReconnect ->
            ConnectionCardState.NeedsReconnect(reconnecting = s.reconnecting)
        is GoogleHealthViewModel.UiState.Error -> ConnectionCardState.Error(s.message)
    }

    ConnectionCard(
        title = "Google Health",
        state = cardState,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onRetry = viewModel::refresh,
    )
}
