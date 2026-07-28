package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.api.googlehealth.GoogleHealthHealthCheckService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cloud Run Job entrypoint for the periodic Google Health connection
 * health-check. Probes every connected user's refresh token; a dead one is
 * marked broken and the user gets a reconnect push (see
 * {@link GoogleHealthHealthCheckService}).
 *
 * <p>Activation mirrors {@link ReevaluateSustainedJob}: this component only
 * loads under the {@code job-gh-health-check} Spring profile, which the
 * deployed Cloud Run Job sets via {@code SPRING_PROFILES_ACTIVE}. Returning
 * normally from {@link #run} lets Spring shut the context down and the JVM
 * exit 0. See {@code infra/scripts/deploy-gh-health-check-job.sh} and
 * {@code infra/scripts/bootstrap-gh-health-check-scheduler.sh}.
 */
@Component
@Profile("job-gh-health-check")
public class GoogleHealthHealthCheckJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(GoogleHealthHealthCheckJob.class.getName());

    private final GoogleHealthHealthCheckService healthCheck;

    public GoogleHealthHealthCheckJob(GoogleHealthHealthCheckService healthCheck) {
        this.healthCheck = healthCheck;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO, "GoogleHealthHealthCheckJob: starting");
        GoogleHealthHealthCheckService.Summary summary = healthCheck.checkAll();
        log.log(System.Logger.Level.INFO, "GoogleHealthHealthCheckJob: done " + summary);
    }
}
