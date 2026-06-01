package com.gte619n.healthfitness.mobile.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.data.auth.AuthState

@Composable
fun SignInScreen(
    state: AuthState,
    onSignIn: () -> Unit,
    onAddAccount: () -> Unit = {},
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

            when (state) {
                AuthState.NoAccount -> {
                    Text(
                        "You'll need a Google account on this device to sign in. " +
                            "Add one, then come back to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onAddAccount) {
                        Text("Add a Google account")
                    }
                    TextButton(onClick = onSignIn) {
                        Text("I've added one — try again")
                    }
                }

                else -> {
                    Text(
                        "Sign in with Google to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when (state) {
                        AuthState.Loading -> CircularProgressIndicator()
                        is AuthState.Failed -> Text(
                            "Sign-in failed: ${state.cause}",
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> Unit
                    }
                    Button(onClick = onSignIn, enabled = state !is AuthState.Loading) {
                        Text("Continue with Google")
                    }
                }
            }
        }
    }
}
