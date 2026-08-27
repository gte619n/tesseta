package com.gte619n.healthfitness.core.nutrition.jobs;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * In-process {@link NutritionJobQueue} used in dev and tests, and as a safe
 * default when Cloud Tasks is not configured ({@code app.nutrition.jobs.mode}
 * unset or {@code local}). Unlike the previous bare
 * {@code CompletableFuture.runAsync} — which used the shared
 * {@code ForkJoinPool.commonPool()} with no bound — this runs jobs on a small
 * fixed pool with a bounded queue and a caller-runs saturation policy, so a burst
 * of captures or a backfill can't spawn unbounded concurrent Gemini calls (the
 * root of the rate-limit / OOM / cost spikes). It is not durable: a job in flight
 * when the instance dies is lost, and only the day-read reconcile sweeps recover
 * it — which is exactly why production should run the Cloud Tasks adapter.
 *
 * <p>Runs each job once; on failure it records the terminal failure immediately
 * (no retry), preserving the pre-Tier-3 single-attempt behaviour.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.jobs.mode", havingValue = "local", matchIfMissing = true)
public class LocalNutritionJobQueue implements NutritionJobQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalNutritionJobQueue.class);

    private final NutritionJobDispatcher dispatcher;
    private final ThreadPoolExecutor executor;

    public LocalNutritionJobQueue(
        NutritionJobDispatcher dispatcher,
        @Value("${app.nutrition.jobs.local.threads:4}") int threads,
        @Value("${app.nutrition.jobs.local.queue-capacity:500}") int queueCapacity
    ) {
        this.dispatcher = dispatcher;
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
            r -> {
                Thread t = new Thread(r, "nutrition-job-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            // Backpressure: when the bounded queue is full, run on the calling
            // thread rather than dropping work or growing without limit.
            new ThreadPoolExecutor.CallerRunsPolicy());
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public void enqueue(NutritionJob job) {
        if (job == null) {
            return;
        }
        executor.execute(() -> {
            try {
                dispatcher.dispatch(job);
            } catch (RuntimeException e) {
                log.warn("Nutrition job {} ({}) failed; marking failed: {}",
                    job.type(), job.id(), e.getMessage());
                safeMarkFailed(job);
            }
        });
    }

    private void safeMarkFailed(NutritionJob job) {
        try {
            dispatcher.markFailed(job);
        } catch (RuntimeException e) {
            log.warn("Nutrition job {} ({}) mark-failed errored: {}",
                job.type(), job.id(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
