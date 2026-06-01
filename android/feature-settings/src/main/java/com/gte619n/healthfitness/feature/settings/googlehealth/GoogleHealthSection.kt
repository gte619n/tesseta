package com.gte619n.healthfitness.feature.settings.googlehealth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.consentRequests.collect { sender ->
            launcher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    HfCard(transparent = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Google Health")

            when (val s = state) {
                is GoogleHealthViewModel.UiState.Loading -> {
                    Text("Loading…")
                }

                is GoogleHealthViewModel.UiState.Disconnected -> {
                    Pill(text = "Not connected", tone = HfTone.Neutral)
                    Button(
                        onClick = { viewModel.connect() },
                        enabled = !s.connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s.connecting) "Connecting…" else "Connect Google Health")
                    }
                }

                is GoogleHealthViewModel.UiState.Connected -> {
                    Pill(text = "Connected", tone = HfTone.Good)
                    s.connectedAtEpochSeconds?.let {
                        Text("Connected ${formatTimestamp(it)}")
                    }
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        enabled = !s.disconnecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s.disconnecting) "Disconnecting…" else "Disconnect")
                    }
                }

                is GoogleHealthViewModel.UiState.Error -> {
                    Pill(text = "Error", tone = HfTone.Alert)
                    Text(s.message)
                    OutlinedButton(
                        onClick = { viewModel.refresh() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

private fun formatTimestamp(epochSeconds: Long): String =
    timestampFormatter.format(
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()),
    )
