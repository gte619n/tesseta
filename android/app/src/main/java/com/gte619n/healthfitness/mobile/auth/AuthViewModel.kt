package com.gte619n.healthfitness.mobile.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import com.gte619n.healthfitness.mobile.di.ApplicationScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Replaces the IMPL-02 `AuthCoordinator` — same body, now a Hilt-managed
 * `ViewModel` so feature screens can `hiltViewModel()` it and there's no
 * manual construction on the activity. The application-scoped coroutine
 * is injected (rather than reached via a singleton) so background work
 * that must outlive the activity has a managed home; it isn't used here
 * yet but the seam is present for AND-02's wear-token publish flow.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val repo: GoogleAuthRepository,
    private val cache: IdTokenCache,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Loading)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { bootstrap() }
    }

    fun interactiveSignIn() {
        viewModelScope.launch {
            _uiState.value = AuthUiState.Loading
            _uiState.value = repo.interactiveSignIn().toUiState()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            repo.signOut()
            _uiState.value = AuthUiState.SignedOut
        }
    }

    private suspend fun bootstrap() {
        val snapshot = cache.read()
        if (!snapshot.hasSignedIn) {
            _uiState.value = AuthUiState.SignedOut
            return
        }
        val cachedToken = snapshot.idToken
        if (snapshot.isFresh() && cachedToken != null) {
            // Surface the cached identity instantly; refresh in the
            // background so subsequent requests get a fresh token.
            _uiState.value = AuthUiState.SignedIn(
                userId = "(cached)",
                email = null,
                displayName = null,
            )
            val refreshed = repo.silentRefresh().toUiState()
            _uiState.value = when (refreshed) {
                AuthUiState.SignedOut, is AuthUiState.Failed -> _uiState.value
                else -> refreshed
            }
        } else {
            _uiState.value = repo.silentRefresh().toUiState()
        }
    }

    private fun AuthState.toUiState(): AuthUiState = when (this) {
        AuthState.Loading -> AuthUiState.Loading
        AuthState.SignedOut -> AuthUiState.SignedOut
        is AuthState.SignedIn -> AuthUiState.SignedIn(userId, email, displayName)
        is AuthState.Failed -> AuthUiState.Failed(cause)
    }
}

/**
 * UI-facing slice of [AuthState]. We deliberately drop `idToken` from the
 * Composable layer — the token is a network-stack concern handled by the
 * auth-aware OkHttp interceptor.
 */
sealed interface AuthUiState {
    data object Loading : AuthUiState
    data object SignedOut : AuthUiState
    data class SignedIn(
        val userId: String,
        val email: String?,
        val displayName: String?,
    ) : AuthUiState

    data class Failed(val message: String) : AuthUiState
}

/**
 * Shim so the existing IMPL-02 `SignInScreen` can stay unchanged in this
 * IMPL — it still consumes the original `AuthState`. Removed once AND-02
 * lifts the screen's signature.
 */
fun AuthUiState.toLegacyAuthState(): AuthState = when (this) {
    AuthUiState.Loading -> AuthState.Loading
    AuthUiState.SignedOut -> AuthState.SignedOut
    is AuthUiState.SignedIn -> AuthState.SignedIn(
        userId = userId,
        email = email,
        displayName = displayName,
        // SignInScreen doesn't display the token; pass empty so we don't
        // need to thread it through the UI layer.
        idToken = "",
    )
    is AuthUiState.Failed -> AuthState.Failed(message)
}
