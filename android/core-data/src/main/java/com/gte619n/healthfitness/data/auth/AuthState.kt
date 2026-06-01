package com.gte619n.healthfitness.data.auth

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState

    /**
     * The device has no Google account to sign in with. Distinct from
     * [SignedOut] so the UI can explain the situation and offer to open the
     * system Add-Account screen rather than looping on a sign-in that can't
     * succeed.
     */
    data object NoAccount : AuthState
    data class SignedIn(
        val userId: String,
        val email: String?,
        val displayName: String?,
        val idToken: String,
    ) : AuthState
    data class Failed(val cause: String) : AuthState
}
