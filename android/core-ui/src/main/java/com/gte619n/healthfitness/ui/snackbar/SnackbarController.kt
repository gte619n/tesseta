package com.gte619n.healthfitness.ui.snackbar

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A snackbar message with optional error styling. */
data class SnackbarMessage(val text: String, val isError: Boolean = false)

/**
 * Hilt-injectable snackbar bus (IMPL-AND-00). ViewModels call [show]; the
 * Compose Scaffold collects [messages] and forwards to a [SnackbarHostState].
 *
 * It is a plain singleton (not a CompositionLocal) so it can be injected into
 * `@HiltViewModel`s — see IMPL-AND-03 / IMPL-AND-05 which surface failures from
 * the ViewModel layer. A [LocalSnackbarController] is also provided for
 * composables that want to show a message directly.
 */
class SnackbarController {
    // extraBufferCapacity so emits from non-suspending VM code never drop.
    private val _messages = MutableSharedFlow<SnackbarMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<SnackbarMessage> = _messages.asSharedFlow()

    fun show(message: SnackbarMessage) {
        _messages.tryEmit(message)
    }

    fun show(text: String, isError: Boolean = false) = show(SnackbarMessage(text, isError))

    fun showError(text: String) = show(SnackbarMessage(text, isError = true))
}

val LocalSnackbarController = staticCompositionLocalOf<SnackbarController> {
    error("SnackbarController not provided — wrap content with ProvideSnackbarController")
}

/**
 * Provides a [SnackbarController] and bridges its messages into the given
 * [hostState]. Pass the same controller instance the Hilt graph provides so
 * VM-emitted and composable-emitted messages share one stream.
 */
@Composable
fun ProvideSnackbarController(
    controller: SnackbarController,
    hostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    LaunchedEffect(controller, hostState) {
        controller.messages.collect { msg ->
            hostState.showSnackbar(msg.text)
        }
    }
    CompositionLocalProvider(LocalSnackbarController provides controller, content = content)
}
