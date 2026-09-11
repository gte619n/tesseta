package com.gte619n.healthfitness.feature.settings.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.InlineLoading
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.components.SettingsCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Shared card UI for third-party connections (Google Health, Withings) so both
 * render identically: status pill + copy + one right-aligned action. The filled
 * button is reserved for the connect/reconnect primary action; disconnect and
 * retry are outlined.
 */
sealed interface ConnectionCardState {
    data object Loading : ConnectionCardState
    data class Disconnected(val connecting: Boolean, val description: String? = null) : ConnectionCardState
    data class Connected(val connectedAtEpochSeconds: Long?, val disconnecting: Boolean) : ConnectionCardState
    data class NeedsReconnect(val reconnecting: Boolean) : ConnectionCardState
    data class Error(val message: String) : ConnectionCardState
}

@Composable
fun ConnectionCard(
    title: String,
    state: ConnectionCardState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
) {
    SettingsCard(title) {
        when (state) {
            is ConnectionCardState.Loading -> InlineLoading()

            is ConnectionCardState.Disconnected -> {
                Pill(text = "Not connected", tone = HfTone.Neutral)
                state.description?.let {
                    Text(it, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
                }
                CardAction(
                    label = if (state.connecting) "Connecting…" else "Connect",
                    enabled = !state.connecting,
                    filled = true,
                    onClick = onConnect,
                )
            }

            is ConnectionCardState.Connected -> {
                Pill(text = "Connected", tone = HfTone.Good)
                state.connectedAtEpochSeconds?.let {
                    Text(
                        "Connected ${formatTimestamp(it)}",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
                CardAction(
                    label = if (state.disconnecting) "Disconnecting…" else "Disconnect",
                    enabled = !state.disconnecting,
                    filled = false,
                    onClick = onDisconnect,
                )
            }

            is ConnectionCardState.NeedsReconnect -> {
                Pill(text = "Reconnect needed", tone = HfTone.Alert)
                Text(
                    "Your $title connection expired and data has stopped syncing. " +
                        "Reconnect to resume.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
                CardAction(
                    label = if (state.reconnecting) "Reconnecting…" else "Reconnect",
                    enabled = !state.reconnecting,
                    filled = true,
                    onClick = onConnect,
                )
            }

            is ConnectionCardState.Error -> {
                Pill(text = "Error", tone = HfTone.Alert)
                Text(state.message, style = Hf.type.bodySm, color = Hf.colors.alert)
                CardAction(label = "Retry", enabled = true, filled = false, onClick = onRetry)
            }
        }
    }
}

@Composable
private fun CardAction(
    label: String,
    enabled: Boolean,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (filled) {
            Button(onClick = onClick, enabled = enabled) { Text(label) }
        } else {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(label) }
        }
    }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)

private fun formatTimestamp(epochSeconds: Long): String =
    timestampFormatter.format(
        Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()),
    )
