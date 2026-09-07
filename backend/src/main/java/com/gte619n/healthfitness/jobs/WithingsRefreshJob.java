package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.api.withings.WithingsRefreshService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cloud Run Job entrypoint for the periodic Withings data re-pull. Walks every
 * connected user and re-pulls a short trailing window of sleep + weight/body-fat
 * — the safety net for missed notify callbacks or a lapsed subscription (see
 * {@link WithingsRefreshService}). It also exercises each (rotating) refresh
 * token, so a dead connection is caught and the user gets a reconnect push.
 *
 * <p>Activation mirrors {@code GoogleHealthRefreshJob}: this component only
 * loads under the {@code job-withings-refresh} Spring profile, which the
 * deployed Cloud Run Job sets via {@code SPRING_PROFILES_ACTIVE}. See
 * {@code infra/scripts/deploy-withings-refresh-job.sh} and
 * {@code infra/scripts/bootstrap-withings-refresh-scheduler.sh}.
 *
 * <p>Exit: the job runs the same image as the web service, so the embedded
 * servlet container keeps the JVM alive once this runner returns. We trigger an
 * orderly Spring shutdown on a separate thread (so the runner returns first) and
 * exit, ending the task cleanly — mirrors {@code GoogleHealthRefreshJob}.
 */
@Component
@Profile("job-withings-refresh")
public class WithingsRefreshJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(WithingsRefreshJob.class.getName());

    private final WithingsRefreshService refresh;
    private final ConfigurableApplicationContext context;

    public WithingsRefreshJob(
        WithingsRefreshService refresh,
        ConfigurableApplicationContext context
    ) {
        this.refresh = refresh;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            log.log(System.Logger.Level.INFO, "WithingsRefreshJob: starting");
            WithingsRefreshService.Summary summary = refresh.refreshAll();
            log.log(System.Logger.Level.INFO, "WithingsRefreshJob: done " + summary);
        } finally {
            Thread shutdown = new Thread(() -> System.exit(SpringApplication.exit(context)),
                "withings-refresh-shutdown");
            shutdown.start();
        }
    }
}
