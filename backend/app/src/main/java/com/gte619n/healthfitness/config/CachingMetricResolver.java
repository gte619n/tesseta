package com.gte619n.healthfitness.config;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.eval.FirestoreMetricResolver;
import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.eval.MetricResolver;
import com.gte619n.healthfitness.core.goals.eval.MetricValue;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * {@link MetricResolver} decorator that dedupes {@code resolve} calls
 * within a single HTTP request via {@link RequestScopedMetricCache}.
 *
 * <p>Marked {@link Primary} so it is the resolver every collaborator
 * ({@code StepEvaluationService}, {@code UserHealthSnapshotService},
 * {@code GoalController} wiring) gets injected — no call-site changes
 * needed. It delegates to the real {@link FirestoreMetricResolver} for the
 * actual reads.
 *
 * <p>When no request scope is active (Spring event listeners reacting to
 * {@code MetricChangedEvent}, batch jobs like the daily SUSTAINED
 * re-evaluation) the cache bean can't be created, so this delegates
 * straight through with no caching. {@code sustainedHolds} and {@code
 * countSince} are always delegated as-is — only {@code resolve} is the
 * hot, repeated call worth memoizing.
 */
@Service
@Primary
public class CachingMetricResolver implements MetricResolver {

    private final FirestoreMetricResolver delegate;
    private final ObjectProvider<RequestScopedMetricCache> cacheProvider;

    public CachingMetricResolver(
        FirestoreMetricResolver delegate,
        ObjectProvider<RequestScopedMetricCache> cacheProvider
    ) {
        this.delegate = delegate;
        this.cacheProvider = cacheProvider;
    }

    @Override
    public MetricValue resolve(String userId, MetricKey key) {
        if (RequestContextHolder.getRequestAttributes() == null) {
            // No request scope (event/batch) — resolve directly.
            return delegate.resolve(userId, key);
        }
        RequestScopedMetricCache cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            return delegate.resolve(userId, key);
        }
        return cache.computeIfAbsent(userId, key, () -> delegate.resolve(userId, key));
    }

    @Override
    public boolean sustainedHolds(
        String userId, MetricKey key, Comparator cmp, double target, int windowDays
    ) {
        return delegate.sustainedHolds(userId, key, cmp, target, windowDays);
    }

    @Override
    public long countSince(String userId, MetricKey key, Instant from) {
        return delegate.countSince(userId, key, from);
    }
}
