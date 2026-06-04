package com.gte619n.healthfitness.config;

import org.springframework.stereotype.Component;

/**
 * Runs an SSE response body off the request thread.
 *
 * The app runs virtual-thread-per-request, so production streams each body on
 * its own virtual thread — identical to the inline {@code Thread.startVirtualThread}
 * this replaces. Centralizing it behind one seam lets tests swap in a
 * synchronous runner (see {@code TestPersistenceConfig}): under MockMvc the
 * streaming thread and {@code asyncDispatch}'s Spring Security header writers
 * both touch the non-thread-safe {@code MockHttpServletResponse}, and running
 * the stream inline makes it finish before the dispatch, removing that race.
 */
@Component
public class SseStreamer {
    public void stream(Runnable task) {
        Thread.startVirtualThread(task);
    }
}
