package com.gte619n.healthfitness.network.sse

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import javax.inject.Inject

/**
 * High-level shape of an SSE stream emission. We collapse OkHttp's
 * callback-style listener into a single sealed type so callers can
 * `collect { when (it) { ... } }` without a custom callback object.
 */
sealed interface SseEvent {
    data object Open : SseEvent
    data class Data(val name: String?, val payload: String) : SseEvent
    data class Failure(val cause: Throwable?, val response: Response?) : SseEvent
    data object Closed : SseEvent
}

/**
 * Wraps OkHttp's `EventSource.Factory` in a coroutine `Flow`. The stream
 * is hot — the underlying connection opens on `collect` and is cancelled
 * on `Flow` cancellation via `awaitClose`. A `Failure` is emitted before
 * the flow closes with the same throwable, so collectors that only care
 * about the terminal cause can wrap the call in `try`/`catch`.
 */
class SseConsumer @Inject constructor(
    private val factory: EventSource.Factory,
) {
    fun stream(request: Request): Flow<SseEvent> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                trySend(SseEvent.Open)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                trySend(SseEvent.Data(name = type, payload = data))
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(SseEvent.Closed)
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                trySend(SseEvent.Failure(t, response))
                close(t)
            }
        }
        val source = factory.newEventSource(request, listener)
        awaitClose { source.cancel() }
    }
}
