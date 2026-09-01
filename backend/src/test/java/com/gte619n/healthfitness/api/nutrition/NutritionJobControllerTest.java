package com.gte619n.healthfitness.api.nutrition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Auth contract for the internal Cloud Tasks handler. The regression guarded here
 * is the trailing-newline secret: a Secret Manager value created with {@code echo}
 * carries a {@code \n} that the HTTP layer strips from the header the queue sends,
 * so a raw (untrimmed) comparison rejected every real delivery with 401 — which
 * silently failed all nutrition background jobs (meal analysis, image generation).
 */
class NutritionJobControllerTest {

    private static final NutritionJob JOB =
        NutritionJob.mealAnalysis("u1", "2026-09-01", "e1", "ref", "image/jpeg");

    @Test
    void acceptsWhenConfiguredSecretHasTrailingNewlineButHeaderIsTrimmed() {
        NutritionJobDispatcher dispatcher = mock(NutritionJobDispatcher.class);
        // Configured secret as mounted from Secret Manager: trailing newline.
        NutritionJobController controller = new NutritionJobController(dispatcher, "s3cr3t\n", 5);

        // Header value as delivered by Cloud Tasks: the newline is gone.
        ResponseEntity<Void> response = controller.handle("s3cr3t", 0, JOB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(dispatcher, times(1)).dispatch(JOB);
    }

    @Test
    void rejectsGenuineMismatch() {
        NutritionJobDispatcher dispatcher = mock(NutritionJobDispatcher.class);
        NutritionJobController controller = new NutritionJobController(dispatcher, "s3cr3t", 5);

        ResponseEntity<Void> response = controller.handle("wrong", 0, JOB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(dispatcher, never()).dispatch(JOB);
    }

    @Test
    void rejectsWhenNoSecretConfigured() {
        NutritionJobDispatcher dispatcher = mock(NutritionJobDispatcher.class);
        NutritionJobController controller = new NutritionJobController(dispatcher, "  ", 5);

        ResponseEntity<Void> response = controller.handle("anything", 0, JOB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(dispatcher, never()).dispatch(JOB);
    }

    @Test
    void marksFailedOnFinalAttempt() {
        NutritionJobDispatcher dispatcher = mock(NutritionJobDispatcher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(dispatcher).dispatch(JOB);
        NutritionJobController controller = new NutritionJobController(dispatcher, "s3cr3t", 5);

        // retryCount 4 == the 5th (final) attempt for max-attempts=5.
        ResponseEntity<Void> response = controller.handle("s3cr3t", 4, JOB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dispatcher, times(1)).markFailed(JOB);
    }
}
