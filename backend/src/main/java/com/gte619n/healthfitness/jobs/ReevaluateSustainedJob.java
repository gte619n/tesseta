package com.gte619n.healthfitness.jobs;

import com.gte619n.healthfitness.core.goals.eval.StepEvaluationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Cloud Run Job entrypoint for the daily SUSTAINED Step re-evaluation
 * pass (IMPL-12 Phase 5).
 *
 * <p>Activation: this component only loads when the Spring profile
 * {@code job-sustained} is active. The deployed Cloud Run Job sets
 * {@code SPRING_PROFILES_ACTIVE=job-sustained}; the long-running web
 * service does not, so the {@link CommandLineRunner} only fires inside
 * the job execution.</p>
 *
 * <p>Exit semantics: returning normally from {@link #run} is enough.
 * Spring's {@code SpringApplication.run} blocks on the
 * {@code ConfigurableApplicationContext}; once the runner finishes the
 * web server is absent (web server bean is profile-gated off in real
 * deployment by simply not having any blocking servlet container with
 * the job-sustained profile), and the JVM exits with status 0. We
 * deliberately do <em>not</em> call {@code System.exit} — that would
 * skip the orderly Spring shutdown lifecycle.</p>
 *
 * <p>The job runs against the same Docker image as the web service, with
 * the same secret bindings and the same runtime service account. See
 * {@code infra/scripts/deploy-goals-sustained-job.sh} and
 * {@code infra/scripts/bootstrap-goals-scheduler.sh}.</p>
 */
@Component
@Profile("job-sustained")
public class ReevaluateSustainedJob implements CommandLineRunner {

    private static final System.Logger log =
        System.getLogger(ReevaluateSustainedJob.class.getName());

    private final StepEvaluationService evaluator;

    public ReevaluateSustainedJob(StepEvaluationService evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public void run(String... args) {
        log.log(System.Logger.Level.INFO, "ReevaluateSustainedJob: starting");
        evaluator.reevaluateAllSustained();
        log.log(System.Logger.Level.INFO, "ReevaluateSustainedJob: done");
        // CommandLineRunner returning normally — Spring shutdown takes over
        // and the JVM exits with 0 after the context closes.
    }
}
