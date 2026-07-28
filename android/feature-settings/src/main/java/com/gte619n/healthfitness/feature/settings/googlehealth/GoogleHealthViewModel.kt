package com.gte619n.healthfitness.feature.settings.googlehealth

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.auth.GoogleHealthScopeRepository
import com.gte619n.healthfitness.data.auth.HealthAuthFlow
import com.gte619n.healthfitness.data.googlehealth.GoogleHealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GoogleHealthViewModel @Inject constructor(
    private val repo: GoogleHealthRepository,
    private val scope: GoogleHealthScopeRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Disconnected(val connecting: Boolean = false) : UiState
        data class Connected(
            val connectedAtEpochSeconds: Long?,
            val disconnecting: Boolean = false,
        ) : UiState
        // Connected but the refresh token has died — the user must reconnect.
        // Reconnecting reuses the same consent flow as a first-time connect.
        data class NeedsReconnect(
            val brokenReason: String? = null,
            val reconnecting: Boolean = false,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Channel, not StateFlow: each consent intent must launch exactly once.
    // StateFlow's replay would re-launch the intent on recomposition.
    private val _consentRequests = Channel<IntentSender>(capacity = 1)
    val consentRequests: Flow<IntentSender> = _consentRequests.receiveAsFlow()

    init {
        // Actively probe on open so a silently-dead connection surfaces as
        // NeedsReconnect immediately, not just after the next webhook. Falls
        // back to a plain status read if the probe endpoint fails.
        checkConnection()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            applyStatus(repo.status())
        }
    }

    private fun checkConnection() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val probed = repo.check()
            applyStatus(if (probed.isSuccess) probed else repo.status())
        }
    }

    private fun applyStatus(result: Result<com.gte619n.healthfitness.domain.googlehealth.GoogleHealthStatus>) {
        result.fold(
            onSuccess = { status ->
                _state.value = when {
                    !status.connected -> UiState.Disconnected()
                    status.needsReconnect -> UiState.NeedsReconnect(status.brokenReason)
                    else -> UiState.Connected(status.connectedAtEpochSeconds)
                }
            },
            onFailure = {
                _state.value = UiState.Error(it.message ?: "Failed to load status")
            },
        )
    }

    fun connect() {
        // Reflect progress on whichever entry point launched the flow: a
        // first-time connect (Disconnected) or a heal (NeedsReconnect).
        _state.value = when (val current = _state.value) {
            is UiState.NeedsReconnect -> current.copy(reconnecting = true)
            else -> UiState.Disconnected(connecting = true)
        }
        viewModelScope.launch {
            when (val flow = scope.requestHealthAuthorization()) {
                is HealthAuthFlow.Resolved -> submitAuthCode(flow.serverAuthCode)
                is HealthAuthFlow.NeedsUserConsent ->
                    _consentRequests.send(flow.intentSender)
                is HealthAuthFlow.Failed ->
                    _state.value = UiState.Error(flow.cause)
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            when (val flow = scope.parseConsentResult(data)) {
                is HealthAuthFlow.Resolved -> submitAuthCode(flow.serverAuthCode)
                is HealthAuthFlow.NeedsUserConsent ->
                    _consentRequests.send(flow.intentSender)
                is HealthAuthFlow.Failed ->
                    _state.value = UiState.Error(flow.cause)
            }
        }
    }

    fun disconnect() {
        val current = _state.value
        if (current is UiState.Connected) {
            _state.value = current.copy(disconnecting = true)
        }
        viewModelScope.launch {
            repo.disconnect().fold(
                onSuccess = { _state.value = UiState.Disconnected() },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to disconnect")
                },
            )
        }
    }

    private suspend fun submitAuthCode(code: String) {
        repo.connectWithServerAuthCode(code).fold(
            onSuccess = { refresh() },
            onFailure = {
                _state.value = UiState.Error(it.message ?: "Failed to connect")
            },
        )
    }
}
