package com.gte619n.healthfitness.core.nutrition.jobs;

/**
 * Thrown by a job's {@code *OrThrow} work method when the work did not complete
 * (generation returned nothing, or an upstream call errored). It signals the
 * queue to retry: the local executor marks the record failed after one attempt,
 * while the Cloud Tasks handler returns a retryable status so the task is
 * redelivered with backoff — and only marks the record failed once the queue's
 * retry budget is exhausted.
 */
public class NutritionJobException extends RuntimeException {

    public NutritionJobException(String message) {
        super(message);
    }

    public NutritionJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
