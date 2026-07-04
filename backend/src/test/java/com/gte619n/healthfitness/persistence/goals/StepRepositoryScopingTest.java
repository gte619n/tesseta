package com.gte619n.healthfitness.persistence.goals;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-4: the metric-key / sustained lookups must be scoped to the requesting
 * user (their goals/phases/steps), never leaking another user's steps. This is
 * the correctness invariant the userbase-wide collectionGroup scan filtered for
 * client-side; the per-user walk must preserve it while reading only the user's
 * own data. Runs against the emulator with two users sharing a metric key.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class StepRepositoryScopingTest {

    private static final String METRIC = "STEPS_7D";

    @Test
    void metricKeyAndSustainedLookupsReturnOnlyTheRequestingUsersSteps(Firestore firestore)
        throws Exception {
        // Two users, same metric key + SUSTAINED kind. user-1 must never see user-2's step.
        seedStep(firestore, "user-1", "g1", "p1", "s1");
        seedStep(firestore, "user-2", "g2", "p2", "s2");

        FirestoreStepRepository repo = new FirestoreStepRepository(firestore);

        assertThat(repo.findByMetricKey("user-1", METRIC))
            .extracting(Step::stepId)
            .containsExactly("s1");
        assertThat(repo.findAllSustained("user-1"))
            .extracting(Step::stepId)
            .containsExactly("s1");

        // And the other user is scoped independently.
        assertThat(repo.findByMetricKey("user-2", METRIC))
            .extracting(Step::stepId)
            .containsExactly("s2");
    }

    private void seedStep(Firestore fs, String userId, String goalId, String phaseId, String stepId)
        throws Exception {
        // Goal + phase docs must exist as real documents — Firestore collection
        // queries skip "missing" ancestors created only implicitly by a deep write.
        fs.collection("users").document(userId)
            .collection("goals").document(goalId)
            .set(Map.of("title", "goal")).get();
        fs.collection("users").document(userId)
            .collection("goals").document(goalId)
            .collection("phases").document(phaseId)
            .set(Map.of("title", "phase")).get();

        new FirestoreStepRepository(fs).save(userId, new Step(
            goalId, phaseId, stepId, "step", 0,
            StepKind.SUSTAINED, false, null, false,
            new StepMetricBinding(METRIC, null, 0.0, 7, null)));
    }
}
