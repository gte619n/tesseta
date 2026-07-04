package com.gte619n.healthfitness.testsupport.firestore;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;

/**
 * Shared skip logic for emulator-backed tests. When the {@code firebase} CLI is
 * present the test runs; when it's absent the test is disabled locally but
 * <em>enabled</em> under {@code firestore.emulator.required=true} (CI) so the
 * build fails loudly instead of silently passing.
 */
final class FirestoreEmulatorGuard {

    static ConditionEvaluationResult evaluate() {
        if (FirestoreEmulator.available()) {
            return ConditionEvaluationResult.enabled("firebase CLI available");
        }
        boolean required = Boolean.parseBoolean(
            System.getProperty("firestore.emulator.required", "false"));
        if (required) {
            return ConditionEvaluationResult.enabled(
                "firebase CLI missing but firestore.emulator.required=true — will fail");
        }
        return ConditionEvaluationResult.disabled(
            "firebase CLI not found; skipping emulator-backed test "
                + "(set -Dfirestore.emulator.required=true to enforce)");
    }

    private FirestoreEmulatorGuard() {
    }
}
