package com.gte619n.healthfitness.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Phase-3: the SSE worker must be cancelled when the emitter ends, so a client
 * disconnect / timeout doesn't leave the worker blocked in an upstream Gemini
 * stream forever. Verifies the streamer registers timeout/error/completion
 * callbacks and that firing one interrupts the worker.
 */
class SseStreamerLifecycleTest {

    @Test
    void firingTheTimeoutCallbackInterruptsTheWorker() throws Exception {
        SseStreamer streamer = new SseStreamer();
        SseEmitter emitter = mock(SseEmitter.class);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        Runnable task = () -> {
            started.countDown();
            try {
                Thread.sleep(10_000); // stand-in for a long upstream stream
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                finished.countDown();
            }
        };

        streamer.stream(emitter, task);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // Simulate the container timing out the async request: fire the timeout
        // callback the streamer registered.
        ArgumentCaptor<Runnable> onTimeout = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(onTimeout.capture());
        onTimeout.getValue().run();

        assertThat(finished.await(5, TimeUnit.SECONDS))
            .as("worker should stop promptly once the emitter times out")
            .isTrue();
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void registersTimeoutErrorAndCompletionCallbacks() {
        SseStreamer streamer = new SseStreamer();
        SseEmitter emitter = mock(SseEmitter.class);

        streamer.stream(emitter, () -> { /* returns immediately */ });

        verify(emitter).onTimeout(org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(emitter).onCompletion(org.mockito.ArgumentMatchers.any(Runnable.class));
        verify(emitter).onError(org.mockito.ArgumentMatchers.any());
    }
}
