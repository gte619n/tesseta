package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.core.user.UserService;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * IMPL-MULTIUSER-01 P1.7 (D13) — Cloud Run Job entrypoint for the daily
 * export-then-delete purge. Hard-deletes every account whose 30-day grace window
 * has elapsed ({@code deletionScheduledAt <= now}); reactivation within the
 * window cancels the schedule before the job ever sees it.
 *
 * <p>Activation mirrors {@link WithingsRefreshJob}: this component only loads
 * under the {@code job-data-purge} Spring profile, which the deployed Cloud Run
 * Job sets via {@code SPRING_PROFILES_ACTIVE} (deploy with {@code --memory=2Gi}
 * like the other jobs). The embedded servlet container keeps the JVM alive once
 * {@code run} returns, so we trigger an orderly Spring shutdown on a separate
 * thread and exit cleanly.
 *
 * <p><b>Limitation:</b> {@link UserService#purgeDue} deletes the top-level
 * {@code users/{uid}} document; recursive subcollection cascade is not performed
 * here (see decision log).
 */
@Component
@Profile("job-data-purge")
public class DataPurgeJob implements CommandLineRunner {

    private static final System.Logger log = System.getLogger(DataPurgeJob.class.getName());

    private final UserService userService;
    private final ConfigurableApplicationContext context;

    public DataPurgeJob(UserService userService, ConfigurableApplicationContext context) {
        this.userService = userService;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            log.log(System.Logger.Level.INFO, "DataPurgeJob: starting");
            List<String> purged = userService.purgeDue(Instant.now());
            log.log(System.Logger.Level.INFO, "DataPurgeJob: purged " + purged.size() + " user(s)");
        } finally {
            Thread shutdown = new Thread(() -> System.exit(SpringApplication.exit(context)),
                "data-purge-shutdown");
            shutdown.start();
        }
    }
}
