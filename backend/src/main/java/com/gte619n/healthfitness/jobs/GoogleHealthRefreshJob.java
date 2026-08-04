package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.api.googlehealth.GoogleHealthRefreshService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cloud Run Job entrypoint for the periodic Google Health data re-pull. Walks
 * every connected user and re-pulls a short trailing window of weight,
 * body-fat, steps, sleep, resting-HR and HRV — the safety net for missed
 * webhooks or a lapsed subscription (see {@link GoogleHealthRefreshService}).
 *
 * <p>Activation mirrors {@link GoogleHealthHealthCheckJob}: this component only
 * loads under the {@code job-gh-refresh} Spring profile, which the deployed
 * Cloud Run Job sets via {@code SPRING_PROFILES_ACTIVE}. See
 * {@code infra/scripts/deploy-gh-refresh-job.sh} and
 * {@code infra/scripts/bootstrap-gh-refresh-scheduler.sh}.
 *
 * <p>Exit: the job runs the same image as the web service, so the embedded
 * servlet container keeps the JVM alive once this runner returns — Cloud Run
 * would otherwise kill the task at its timeout and mark the execution failed.
 * We trigger an orderly Spring shutdown on a separate thread (so the runner
 * returns first) and exit, ending the task cleanly. Mirrors
 * {@link SplitImportedWorkoutBlocksJob}. (Note: {@code web-application-type=none}
 * is not an option — {@code SecurityConfig} requires the servlet
 * {@code HttpSecurity}, so the full web context must boot.)
 */
@Component
@Profile("job-gh-refresh")
public class GoogleHealthRefreshJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(GoogleHealthRefreshJob.class.getName());

    private final GoogleHealthRefreshService refresh;
    private final ConfigurableApplicationContext context;

    public GoogleHealthRefreshJob(
        GoogleHealthRefreshService refresh,
        ConfigurableApplicationContext context
    ) {
        this.refresh = refresh;
        this.context = context;
    }

    @Override
    public void run(String... args) {
        try {
            log.log(System.Logger.Level.INFO, "GoogleHealthRefreshJob: starting");
            GoogleHealthRefreshService.Summary summary = refresh.refreshAll();
            log.log(System.Logger.Level.INFO, "GoogleHealthRefreshJob: done " + summary);
        } finally {
            Thread shutdown = new Thread(() -> System.exit(SpringApplication.exit(context)),
                "gh-refresh-shutdown");
            shutdown.start();
        }
    }
}
