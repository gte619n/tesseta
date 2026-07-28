package com.gte619n.healthfitness.api.googlehealth;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Proactively probes every connected user's Google Health token so a dead
 * connection is caught even when no webhook traffic would otherwise exercise
 * it (e.g. a refresh token that expired under Testing-mode's 7-day life while
 * the user wasn't recording data).
 *
 * <p>The probe reuses the single detection chokepoint: calling
 * {@link AccessTokenService#accessTokenFor} exchanges the refresh token, and a
 * dead one throws {@code GoogleHealthAuthException} — which
 * {@code AccessTokenService} records as broken and publishes a reconnect push
 * for. This service just drives the loop and swallows failures so one bad user
 * never aborts the sweep.
 */
@Service
public class GoogleHealthHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthHealthCheckService.class);

    private final UserRepository users;
    private final AccessTokenService tokens;

    public GoogleHealthHealthCheckService(UserRepository users, AccessTokenService tokens) {
        this.users = users;
        this.tokens = tokens;
    }

    /** Probe every user with a Google Health connection. Returns a summary. */
    public Summary checkAll() {
        int connected = 0;
        int healthy = 0;
        int broken = 0;
        for (String userId : users.findAllUserIds()) {
            Optional<User> maybe = users.findById(userId);
            if (maybe.isEmpty() || maybe.get().googleHealth() == null) {
                continue;
            }
            connected++;
            try {
                tokens.accessTokenFor(userId);
                healthy++;
            } catch (RuntimeException e) {
                // GoogleHealthAuthException => already recorded broken + push
                // published by AccessTokenService. Transient errors are logged
                // but leave the connection healthy (re-probed next run).
                broken++;
                log.warn("Google Health probe failed user={}: {}", userId, e.getMessage());
            }
        }
        Summary summary = new Summary(connected, healthy, broken);
        log.info("Google Health health-check: {}", summary);
        return summary;
    }

    public record Summary(int connected, int healthy, int broken) {}
}
