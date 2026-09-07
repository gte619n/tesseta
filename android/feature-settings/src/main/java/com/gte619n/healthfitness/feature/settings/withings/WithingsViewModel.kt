package com.gte619n.healthfitness.feature.settings.withings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.withings.WithingsCallback
import com.gte619n.healthfitness.data.withings.WithingsOAuthCoordinator
import com.gte619n.healthfitness.data.withings.WithingsRepository
import com.gte619n.healthfitness.domain.withings.WithingsStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WithingsViewModel @Inject constructor(
    private val repo: WithingsRepository,
    private val coordinator: WithingsOAuthCoordinator,
    @Named("withingsClientId") private val clientId: String,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Disconnected(val connecting: Boolean = false) : UiState
        data class Connected(
            val connectedAtEpochSeconds: Long?,
            val disconnecting: Boolean = false,
        ) : UiState
        // Connected but the (rotating) refresh token died — the user must
        // reconnect, which reuses the same browser flow as a first connect.
        data class NeedsReconnect(
            val brokenReason: String? = null,
            val reconnecting: Boolean = false,
        ) : UiState
        data class Error(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()

    // One-shot browser-launch requests. Channel, not StateFlow: each authorize
    // URL must launch exactly once (no replay on recomposition).
    private val _authorizeRequests = Channel<String>(capacity = 1)
    val authorizeRequests: Flow<String> = _authorizeRequests.receiveAsFlow()

    init {
        checkConnection()
        observeCallbacks()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch { applyStatus(repo.status()) }
    }

    private fun checkConnection() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val probed = repo.check()
            applyStatus(if (probed.isSuccess) probed else repo.status())
        }
    }

    private fun applyStatus(result: Result<WithingsStatus>) {
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
        if (clientId.isBlank()) {
            _state.value = UiState.Error("Withings is not configured in this build")
            return
        }
        _state.value = when (val current = _state.value) {
            is UiState.NeedsReconnect -> current.copy(reconnecting = true)
            else -> UiState.Disconnected(connecting = true)
        }
        val oauthState = UUID.randomUUID().toString()
        coordinator.rememberState(oauthState)
        viewModelScope.launch {
            _authorizeRequests.send(
                WithingsOAuthCoordinator.buildAuthorizeUrl(clientId, oauthState),
            )
        }
    }

    // Collect the browser redirect relayed by the Activity via the coordinator.
    private fun observeCallbacks() {
        viewModelScope.launch {
            coordinator.callbacks.collect { onCallback(it) }
        }
    }

    private suspend fun onCallback(cb: WithingsCallback) {
        val expected = coordinator.consumeState()
        val code = cb.code
        when {
            cb.error != null ->
                _state.value = UiState.Error("Authorization failed: ${cb.error}")
            code.isNullOrBlank() ->
                _state.value = UiState.Error("Authorization returned no code")
            expected == null || cb.state != expected ->
                // CSRF mismatch (or a stale callback) — refuse the exchange.
                _state.value = UiState.Error("Security check failed. Please try again.")
            else -> repo.connect(code, WithingsOAuthCoordinator.REDIRECT_URI).fold(
                onSuccess = { refresh() },
                onFailure = {
                    _state.value = UiState.Error(it.message ?: "Failed to connect")
                },
            )
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
}
