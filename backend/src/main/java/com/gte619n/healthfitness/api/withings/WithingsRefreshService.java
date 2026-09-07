package com.gte619n.healthfitness.api.withings;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Periodically re-pulls each connected user's recent Withings data over a short
 * trailing window, as a safety net for missed webhook notifications or a lapsed
 * subscription. Mirrors {@code GoogleHealthRefreshService}.
 *
 * <p>It doubles as a liveness probe: each user's re-pull begins by exchanging
 * the (rotating) refresh token via {@link WithingsAccessTokenService}, so a dead
 * connection is detected and marked broken (with a reconnect push) as a side
 * effect. Per-user failures are swallowed so one bad connection never aborts
 * the sweep.
 */
@Service
public class WithingsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(WithingsRefreshService.class);

    private final UserRepository users;
    private final WithingsBackfillService backfill;
    private final int windowDays;

    public WithingsRefreshService(
        UserRepository users,
        WithingsBackfillService backfill,
        @Value("${app.withings.refresh-window-days:14}") int windowDays
    ) {
        this.users = users;
        this.backfill = backfill;
        this.windowDays = windowDays;
    }

    /** Re-pull the recent window for every user with a Withings connection. */
    public Summary refreshAll() {
        int connected = 0;
        int refreshed = 0;
        int failed = 0;
        for (String userId : users.findAllUserIds()) {
            Optional<User> maybe = users.findById(userId);
            if (maybe.isEmpty() || maybe.get().withings() == null) {
                continue;
            }
            connected++;
            try {
                backfill.runBackfill(userId, windowDays);
                refreshed++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("Withings refresh failed user={}: {}", userId, e.getMessage());
            }
        }
        log.info("Withings refresh sweep: connected={} refreshed={} failed={}",
            connected, refreshed, failed);
        return new Summary(connected, refreshed, failed);
    }

    public record Summary(int connected, int refreshed, int failed) {}
}
