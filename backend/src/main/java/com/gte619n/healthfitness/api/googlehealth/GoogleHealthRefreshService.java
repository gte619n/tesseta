package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Periodically re-pulls each connected user's recent Google Health data
 * (weight, body-fat, steps, sleep, resting-HR, HRV) over a short trailing
 * window.
 *
 * <p>Why this exists: after the one-time connect backfill, the only forward
 * path is Google's push webhooks. If a notification is missed, the webhook
 * subscription lapses, or the refresh token expires (Testing-mode's 7-day
 * life), the dashboard silently goes stale with no fallback. This sweep is
 * that fallback — a bounded re-pull keeps the four visible metrics fresh even
 * when no webhook arrives.
 *
 * <p>It also doubles as a liveness probe: each user's backfill starts by
 * exchanging the refresh token via {@link AccessTokenService}, so a dead
 * connection is detected and marked broken (with a reconnect push) exactly as
 * {@link GoogleHealthHealthCheckService} does — no separate probe needed.
 *
 * <p>Per-user failures are swallowed so one bad connection never aborts the
 * sweep. Mirrors {@link GoogleHealthHealthCheckService}.
 */
@Service
public class GoogleHealthRefreshService {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthRefreshService.class);

    private final UserRepository users;
    private final BackfillService bodyCompositionBackfill;
    private final DailyMetricBackfillService dailyMetricBackfill;
    private final int windowDays;

    public GoogleHealthRefreshService(
        UserRepository users,
        BackfillService bodyCompositionBackfill,
        DailyMetricBackfillService dailyMetricBackfill,
        @Value("${app.googlehealth.refresh-window-days:14}") int windowDays
    ) {
        this.users = users;
        this.bodyCompositionBackfill = bodyCompositionBackfill;
        this.dailyMetricBackfill = dailyMetricBackfill;
        this.windowDays = windowDays;
    }

    /** Re-pull the recent window for every user with a Google Health connection. */
    public Summary refreshAll() {
        int connected = 0;
        int refreshed = 0;
        int failed = 0;
        for (String userId : users.findAllUserIds()) {
            Optional<User> maybe = users.findById(userId);
            if (maybe.isEmpty() || maybe.get().googleHealth() == null) {
                continue;
            }
            connected++;
            try {
                // Body composition first: it also exchanges the refresh token,
                // so a broken connection is caught here and the daily-metric
                // pull below simply no-ops on the same dead token.
                bodyCompositionBackfill.runBackfill(userId, windowDays);
                dailyMetricBackfill.runBackfill(userId, windowDays);
                refreshed++;
            } catch (RuntimeException e) {
                // A dead refresh token is already recorded broken (+ reconnect
                // push) by AccessTokenService; transient errors are logged and
                // retried on the next sweep. Never abort the whole run.
                failed++;
                log.warn("Google Health refresh failed user={}: {}", userId, e.getMessage());
            }
        }
        Summary summary = new Summary(connected, refreshed, failed, windowDays);
        log.info("Google Health refresh: {}", summary);
        return summary;
    }

    public record Summary(int connected, int refreshed, int failed, int windowDays) {}
}
