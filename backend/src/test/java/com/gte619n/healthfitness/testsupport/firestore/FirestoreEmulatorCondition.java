package com.gte619n.healthfitness.testsupport.firestore;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Skip-guard only (no client injection) for Spring-context emulator tests. */
public final class FirestoreEmulatorCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return FirestoreEmulatorGuard.evaluate();
    }
}
