package com.gte619n.healthfitness.feature.settings.withings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    HfCard(transparent = true) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Withings")

            when (val s = state) {
                is WithingsViewModel.UiState.Loading -> {
                    Text("Loading…")
                }

                is WithingsViewModel.UiState.Disconnected -> {
                    Pill(text = "Not connected", tone = HfTone.Neutral)
                    Text(
                        "Sync sleep from your Sleep Analyzer pad, plus weight and " +
                            "body fat from your Withings scale.",
                    )
                    Button(
                        onClick = { viewModel.connect() },
                        enabled = !s.connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s.connecting) "Connecting…" else "Connect Withings")
                    }
                }

                is WithingsViewModel.UiState.Connected -> {
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

                is WithingsViewModel.UiState.NeedsReconnect -> {
                    Pill(text = "Reconnect needed", tone = HfTone.Alert)
                    Text(
                        "Your Withings connection expired and data has stopped " +
                            "syncing. Reconnect to resume.",
                    )
                    Button(
                        onClick = { viewModel.connect() },
                        enabled = !s.reconnecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (s.reconnecting) "Reconnecting…" else "Reconnect Withings")
                    }
                }

                is WithingsViewModel.UiState.Error -> {
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
