package com.gte619n.healthfitness.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.gte619n.healthfitness.wear.auth.SignInRequiredScreen
import com.gte619n.healthfitness.wear.auth.WearIdTokenCache
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Wear MainActivity. Annotated `@AndroidEntryPoint` so future wear surfaces
 * (IMPL-AND-08) can inject their `@HiltViewModel`s without re-touching
 * this scaffolding. The body still drives "signed in vs signed out" off
 * the wear-side token cache — wear doesn't get a `NavHost` in this IMPL.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var cache: WearIdTokenCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val token by cache.idTokenFlow.collectAsState(initial = null)
                if (token.isNullOrBlank()) {
                    SignInRequiredScreen()
                } else {
                    WearHelloScreen()
                }
            }
        }
    }
}

@Composable
fun WearHelloScreen() {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
    ) {
        item { Text("tesseta", style = MaterialTheme.typography.titleMedium) }
        item { Text("Signed in", style = MaterialTheme.typography.bodySmall) }
    }
}
