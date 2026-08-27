package com.gte619n.healthfitness.core.nutrition.jobs;

/**
 * Port for handing a {@link NutritionJob} to durable background execution
 * (Tier 3). Producers (the capture / describe / image services) call
 * {@link #enqueue} and return immediately; the job runs later — possibly on a
 * different instance — via {@link NutritionJobDispatcher}.
 *
 * <p>Two adapters implement this: a local bounded-executor queue (default; used
 * in dev and tests, and a safe in-process fallback that already caps concurrency
 * unlike the shared {@code ForkJoinPool}), and a Cloud Tasks queue (production)
 * that persists the job so it outlives instance death and is retried with
 * backoff.
 */
public interface NutritionJobQueue {

    /** Durably enqueue a job for later execution. Must not run it inline. */
    void enqueue(NutritionJob job);
}
