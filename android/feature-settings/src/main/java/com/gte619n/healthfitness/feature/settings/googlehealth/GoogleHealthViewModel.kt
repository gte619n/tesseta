package com.gte619n.healthfitness.feature.settings.googlehealth

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.auth.GoogleHealthScopeRepository
import com.gte619n.healthfitness.data.auth.HealthAuthFlow
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
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
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Channel, not StateFlow: each consent intent must launch exactly once.
    // StateFlow's replay would re-launch the intent on recomposition.
    private val _consentRequests = Channel<IntentSender>(capacity = 1)
    val consentRequests: Flow<IntentSender> = _consentRequests.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            repo.status().fold(
                onSuccess = { status ->
                    _state.value = if (status.connected) {
                        UiState.Connected(status.connectedAtEpochSeconds)
                    } else {
                        UiState.Disconnected()
                    }
                },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to load status")
                },
            )
        }
    }

    fun connect() {
        _state.value = UiState.Disconnected(connecting = true)
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
