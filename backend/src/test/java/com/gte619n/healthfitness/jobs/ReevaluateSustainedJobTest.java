package com.gte619n.healthfitness.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.jobs.ReevaluateSustainedJob;
import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test for the {@link ReevaluateSustainedJob} Cloud Run Job entry
 * point under {@code @Profile("job-sustained")}. Verifies:
 *
 * <ol>
 *   <li>The job bean is wired into the Spring context when the profile
 *       is active.</li>
 *   <li>{@link ReevaluateSustainedJob#run} completes without throwing
 *       even with a real Goal/Phase/Step fixture present.</li>
 * </ol>
 *
 * The actual {@code Step.done} flip would require a metric value
 * staged through the live resolver — out of scope here. This test owns
 * the "runs end-to-end" guarantee; the per-resolver-branch tests in
 * {@code core} cover the evaluation semantics.
 */
@SpringBootTest
@ActiveProfiles({"test", "job-sustained"})
@Import(TestPersistenceConfig.class)
class ReevaluateSustainedJobTest {

    @Autowired ReevaluateSustainedJob job;
    @Autowired UserRepository userRepo;
    @Autowired GoalRepository goalRepo;
    @Autowired PhaseRepository phaseRepo;
    @Autowired StepRepository stepRepo;

    @Test
    void jobBeanIsWired() {
        assertThat(job).isNotNull();
    }

    @Test
    void runCompletesEndToEnd_withOneUserAndOneSustainedStep() throws Exception {
        String userId = "user-job-smoke";
        String goalId = "goal-job-smoke";
        String phaseId = "phase-job-smoke";
        String stepId = "step-job-smoke";

        userRepo.save(new User(
            userId, "smoke@test", "smoke",
            null, null, Instant.now(), Instant.now()
        ));
        goalRepo.save(new Goal(
            userId, goalId, "Goal", "desc",
            GoalDomain.CARDIOVASCULAR, GoalStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusMonths(6),
            Instant.now(), Instant.now(), null,
            List.of(phaseId),
            GoalSource.MANUAL
        ));
        phaseRepo.save(userId, new Phase(
            goalId, phaseId, "Phase 0", "desc",
            0, PhaseStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusMonths(2),
            null, new ArrayList<>(List.of(stepId))
        ));
        stepRepo.save(userId, new Step(
            goalId, phaseId, stepId,
            "RHR < 55 for 30d", 0, StepKind.SUSTAINED,
            false, null,
            false,
            new StepMetricBinding("vitals.restingHr", Comparator.LT, 55.0, 30, null)
        ));

        // Drive the same code path Cloud Run would: Spring already
        // invoked run() at context startup because of CommandLineRunner.
        // Call again explicitly so the assertion is independent of
        // startup ordering. Must not throw.
        job.run();

        Step after = stepRepo.findById(userId, goalId, phaseId, stepId).orElseThrow();
        // No metric staged → resolver returns unavailable → SUSTAINED
        // stays undone. The point of this test is that the run completes
        // and the Step is still readable afterwards.
        assertThat(after.done()).isFalse();
    }
}
