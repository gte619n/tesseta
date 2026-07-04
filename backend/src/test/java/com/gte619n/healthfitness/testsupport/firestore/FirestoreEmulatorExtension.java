package com.gte619n.healthfitness.testsupport.firestore;

import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * JUnit 5 extension for context-free Firestore-emulator tests.
 *
 * <ul>
 *   <li><b>Skip guard</b> — disables the test class when the {@code firebase}
 *       CLI is unavailable (unless {@code firestore.emulator.required=true}, in
 *       which case it runs and fails loudly).</li>
 *   <li><b>Injection</b> — resolves a {@link Firestore} parameter wired to the
 *       emulator (one client per class, closed in {@code afterAll}).</li>
 *   <li><b>Isolation</b> — clears all emulator data before each test.</li>
 * </ul>
 *
 * For Spring-context tests use {@link AbstractFirestoreIntegrationTest} instead.
 */
public final class FirestoreEmulatorExtension
    implements ExecutionCondition, BeforeAllCallback, BeforeEachCallback,
    AfterAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NS =
        ExtensionContext.Namespace.create(FirestoreEmulatorExtension.class);

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return FirestoreEmulatorGuard.evaluate();
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        FirestoreEmulator emu = FirestoreEmulator.ensureStarted();
        Firestore client = emu.newClient();
        context.getStore(NS).put(FirestoreEmulator.class, emu);
        context.getStore(NS).put(Firestore.class, client);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        emulator(context).clearData();
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        Firestore client = context.getStore(NS).get(Firestore.class, Firestore.class);
        if (client != null) client.close();
    }

    @Override
    public boolean supportsParameter(ParameterContext pc, ExtensionContext ec) {
        return pc.getParameter().getType() == Firestore.class;
    }

    @Override
    public Object resolveParameter(ParameterContext pc, ExtensionContext ec) {
        // The shared client is put in beforeAll; Store lookups consult ancestor
        // contexts, so this returns the one class-level client (closed in afterAll).
        return ec.getStore(NS).get(Firestore.class, Firestore.class);
    }

    private FirestoreEmulator emulator(ExtensionContext context) {
        return context.getStore(NS).get(FirestoreEmulator.class, FirestoreEmulator.class);
    }
}
