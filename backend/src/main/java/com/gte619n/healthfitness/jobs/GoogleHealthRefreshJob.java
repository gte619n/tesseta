package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.api.googlehealth.GoogleHealthRefreshService;
import org.springframework.boot.CommandLineRunner;
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
 * Cloud Run Job sets via {@code SPRING_PROFILES_ACTIVE}. Returning normally
 * from {@link #run} lets Spring shut the context down and the JVM exit 0. See
 * {@code infra/scripts/deploy-gh-refresh-job.sh} and
 * {@code infra/scripts/bootstrap-gh-refresh-scheduler.sh}.
 */
@Component
@Profile("job-gh-refresh")
public class GoogleHealthRefreshJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(GoogleHealthRefreshJob.class.getName());

    private final GoogleHealthRefreshService refresh;

    public GoogleHealthRefreshJob(GoogleHealthRefreshService refresh) {
        this.refresh = refresh;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO, "GoogleHealthRefreshJob: starting");
        GoogleHealthRefreshService.Summary summary = refresh.refreshAll();
        log.log(System.Logger.Level.INFO, "GoogleHealthRefreshJob: done " + summary);
    }
}
