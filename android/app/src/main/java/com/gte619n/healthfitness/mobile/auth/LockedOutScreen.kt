package com.gte619n.healthfitness.mobile.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * IMPL-MULTIUSER-01 P1.3/P1.4 — the locked-out screens shown when the backend
 * reports a non-active account (`403 + X-Account-Status`). Mirrors
 * [SignInScreen]'s centered single-column layout and `tesseta` wordmark, but is
 * terminal: there is no action that re-authenticates (that would just 403 again),
 * only an optional "Sign out" to return to the sign-in screen.
 */

/** `account-pending` — the user signed in but is awaiting admin approval (P1.3). */
@Composable
fun PendingApprovalScreen(onSignOut: () -> Unit = {}) {
    LockedOutScaffold(
        title = "Request received",
        body = "Your account is pending approval. We'll let you in as soon as an " +
            "administrator approves access — you don't need to do anything else.",
        onSignOut = onSignOut,
    )
}

/**
 * `account-suspended` / `account-disabled` (P1.4) — access has been revoked and
 * local data has already been wiped. [disabled] only changes the copy; both are
 * equally terminal.
 */
@Composable
fun SuspendedScreen(disabled: Boolean, onSignOut: () -> Unit = {}) {
    LockedOutScaffold(
        title = if (disabled) "Account disabled" else "Account suspended",
        body = if (disabled) {
            "This account has been disabled and can no longer access tesseta. " +
                "If you think this is a mistake, contact your administrator."
        } else {
            "Your account has been suspended and is temporarily locked. " +
                "Contact your administrator to restore access."
        },
        onSignOut = onSignOut,
    )
}

@Composable
private fun LockedOutScaffold(
    title: String,
    body: String,
    onSignOut: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("tesseta", style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onSignOut) {
                Text("Sign out")
            }
        }
    }
}
