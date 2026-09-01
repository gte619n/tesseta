package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobDispatcher;
import com.gte619n.healthfitness.integrations.nutrition.CloudTasksNutritionJobQueue;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal handler that Cloud Tasks calls to execute one {@link NutritionJob}
 * (Tier 3). Authenticated by the shared-secret header the queue attaches
 * (mirroring the Google Health webhook), not a user bearer — the path is
 * permit-all in {@code SecurityConfig} and gated here instead.
 *
 * <p>Retry contract, which turns the queue into the retry/terminal engine:
 * <ul>
 *   <li>success → 204, the task is done;</li>
 *   <li>failure with retries left → 503, so Cloud Tasks redelivers with backoff;</li>
 *   <li>failure on the final attempt → mark the record failed and return 200, so
 *       the queue stops and the user sees a failed row they can retry/delete.</li>
 * </ul>
 * Delivery is at-least-once, so {@link NutritionJobDispatcher#dispatch} is
 * idempotent (each work method skips an already-terminal target).
 */
@RestController
@RequestMapping("/internal/nutrition/jobs")
public class NutritionJobController {

    private static final Logger log = LoggerFactory.getLogger(NutritionJobController.class);

    /** Cloud Tasks sets this to the 0-based retry count (first attempt = 0). */
    private static final String RETRY_COUNT_HEADER = "X-CloudTasks-TaskRetryCount";

    private final NutritionJobDispatcher dispatcher;
    private final String secret;
    private final int maxAttempts;

    public NutritionJobController(
        NutritionJobDispatcher dispatcher,
        @Value("${app.nutrition.jobs.secret:}") String secret,
        @Value("${app.nutrition.jobs.max-attempts:5}") int maxAttempts
    ) {
        this.dispatcher = dispatcher;
        // Trim the configured secret: a Secret Manager value created with `echo`
        // carries a trailing newline, which the HTTP layer strips from the header
        // the queue sends — so the raw env value would never match the delivered
        // token and every job 401s. Compare on the trimmed value both sides.
        this.secret = secret == null ? "" : secret.trim();
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @PostMapping
    public ResponseEntity<Void> handle(
        @RequestHeader(value = CloudTasksNutritionJobQueue.SECRET_HEADER, required = false) String token,
        @RequestHeader(value = RETRY_COUNT_HEADER, required = false) Integer retryCount,
        @RequestBody NutritionJob job
    ) {
        String presented = token == null ? "" : token.trim();
        if (secret.isBlank() || !Objects.equals(secret, presented)) {
            // Fail closed: an unset secret (misconfig) or a mismatch is rejected.
            log.warn("Nutrition job rejected — secret missing or mismatched");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int attempt = retryCount == null ? 0 : retryCount;
        try {
            dispatcher.dispatch(job);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException e) {
            boolean lastAttempt = attempt >= maxAttempts - 1;
            if (lastAttempt) {
                log.warn("Nutrition job {} ({}) failed on final attempt {}; marking failed: {}",
                    job.type(), job.id(), attempt, e.getMessage());
                safeMarkFailed(job);
                // 200: acknowledge so Cloud Tasks stops retrying a dead job.
                return ResponseEntity.ok().build();
            }
            log.warn("Nutrition job {} ({}) failed on attempt {}; will retry: {}",
                job.type(), job.id(), attempt, e.getMessage());
            // 503: retryable — Cloud Tasks redelivers with backoff.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private void safeMarkFailed(NutritionJob job) {
        try {
            dispatcher.markFailed(job);
        } catch (RuntimeException e) {
            log.warn("Nutrition job {} ({}) mark-failed errored: {}",
                job.type(), job.id(), e.getMessage());
        }
    }
}
