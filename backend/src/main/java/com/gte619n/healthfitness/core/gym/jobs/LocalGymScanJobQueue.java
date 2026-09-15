package com.gte619n.healthfitness.core.gym.jobs;

import com.gte619n.healthfitness.core.gym.GymVideoScanService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * IMPL-GYM-003: in-process durable-ish queue — the dev/default fallback (mirrors
 * {@code LocalNutritionJobQueue}). Runs {@link GymVideoScanService#runScan} on a
 * bounded executor so the request thread returns immediately. Prod uses a
 * Cloud-Tasks-backed queue (deferred; gate {@code app.gym.jobs.mode=cloud-tasks}).
 *
 * <p>{@link GymVideoScanService} is resolved lazily via {@link ObjectProvider} to
 * break the queue↔service construction cycle. {@code runScan} sets its own
 * terminal state on failure, so we only log here.
 */
@Component
@ConditionalOnProperty(name = "app.gym.jobs.mode", havingValue = "local", matchIfMissing = true)
public class LocalGymScanJobQueue implements GymScanJobQueue {

    private static final Logger log = LoggerFactory.getLogger(LocalGymScanJobQueue.class);

    private final ObjectProvider<GymVideoScanService> service;
    private final ThreadPoolExecutor executor;

    public LocalGymScanJobQueue(
        ObjectProvider<GymVideoScanService> service,
        @Value("${app.gym.jobs.local.threads:2}") int threads,
        @Value("${app.gym.jobs.local.queue-capacity:100}") int capacity
    ) {
        this.service = service;
        this.executor = new ThreadPoolExecutor(
            1, Math.max(1, threads), 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(1, capacity)),
            new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Override
    public void enqueue(GymScanJob job) {
        if (job == null) {
            return;
        }
        executor.execute(() -> {
            try {
                service.getObject().runScan(job);
            } catch (RuntimeException e) {
                log.warn("Gym scan job {} threw: {}", job.scanId(), e.toString());
            }
        });
    }
}
