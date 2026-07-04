package com.gte619n.healthfitness.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs an SSE response body off the request thread and ties its lifecycle to the
 * emitter's.
 *
 * The app runs virtual-thread-per-request, so production streams each body on
 * its own virtual thread. Centralizing it behind one seam lets tests swap in a
 * synchronous runner (override {@link #startWorker}, see {@code TestPersistenceConfig}):
 * under MockMvc the streaming thread and {@code asyncDispatch}'s Spring Security
 * header writers both touch the non-thread-safe {@code MockHttpServletResponse},
 * and running the stream inline makes it finish before the dispatch, removing
 * that race.
 *
 * <p><b>Cancellation:</b> the worker is bound to the emitter's
 * timeout/error/completion callbacks so that when the client disconnects or the
 * emitter times out, the worker is <em>interrupted</em>. Without this the worker
 * kept running — typically blocked inside an upstream Gemini stream — with
 * nothing to stop it, leaking virtual threads + upstream connections and burning
 * quota for an abandoned request. Worker loops should honour interruption (and
 * the Gemini client carries a request timeout as a backstop, see GeminiConfig).
 */
@Component
public class SseStreamer {

    /**
     * Run {@code task} for {@code emitter}'s body, wiring the emitter's lifecycle
     * callbacks to interrupt the worker on timeout/error/completion.
     */
    public void stream(SseEmitter emitter, Runnable task) {
        Thread worker = startWorker(task);
        if (worker != null) {
            emitter.onTimeout(worker::interrupt);
            emitter.onError(t -> worker.interrupt());
            emitter.onCompletion(worker::interrupt);
        }
    }

    /**
     * Start the worker and return its thread (so it can be interrupted), or
     * {@code null} if it ran inline (the test override runs synchronously and has
     * nothing to cancel).
     */
    protected Thread startWorker(Runnable task) {
        return Thread.startVirtualThread(task);
    }
}
