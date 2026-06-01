package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState

// IMPL-AND-01: renders a card body for the given [state], keeping a fixed
// placeholder height while loading so the page does not reflow.
@Composable
fun <T> CardSwitch(
    state: CardState<T>,
    placeholderHeightDp: Int,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is CardState.Loading -> LoadingState(
            modifier = Modifier.fillMaxWidth().height(placeholderHeightDp.dp),
        )
        is CardState.Loaded -> content(state.data)
        is CardState.Error -> ErrorState(
            message = state.message,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
