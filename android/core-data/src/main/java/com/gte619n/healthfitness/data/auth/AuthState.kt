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

    /**
     * IMPL-MULTIUSER-01 P1.3/P1.4 — the account exists and authenticated, but the
     * backend has not granted it full access. Terminal from the client's point of
     * view: the user cannot proceed into the app and cannot resolve it by
     * re-authenticating (a silent/interactive refresh would just 403 again), so we
     * render a dedicated locked-out screen rather than looping on sign-in.
     *
     * Surfaced when a request (or `/api/auth/exchange`|`/refresh`) returns HTTP 403
     * with `X-Account-Status`. Distinguished so each gets its own copy and the
     * suspended/disabled path can wipe local PHI:
     *   - [Pending]   — `account-pending`: awaiting admin approval. Local data is
     *                   left intact (there is none yet for a never-approved user,
     *                   and nothing to protect).
     *   - [Suspended] — `account-suspended` or `account-disabled`: access revoked.
     *                   Local PHI is wiped (see AuthCoordinator). [disabled] marks
     *                   the harder `account-disabled` case for copy/telemetry; both
     *                   wipe identically.
     */
    data object Pending : AuthState
    data class Suspended(val disabled: Boolean) : AuthState
}
