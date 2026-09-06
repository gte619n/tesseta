package com.gte619n.healthfitness.core.progression;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs the session loop when a workout is completed (IMPL-PROG-01 D22). The
 * listener is synchronous, so writeback of next-session prescriptions completes
 * within the completion request. Failures here must never fail the completion —
 * the loop is best-effort per exercise, and this catches anything that escapes.
 */
@Component
public class ProgressionSessionListener {

    private final ProgressionEngine engine;

    public ProgressionSessionListener(ProgressionEngine engine) {
        this.engine = engine;
    }

    @EventListener
    public void onSessionCompleted(SessionCompletedEvent event) {
        try {
            engine.onSessionCompleted(event.userId(), event.session());
        } catch (RuntimeException ignored) {
            // Progression is advisory to completion; never let it break logging.
        }
    }
}
