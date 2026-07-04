package com.gte619n.healthfitness.testsupport.firestore;

import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for Spring-context integration tests that need a real Firestore.
 *
 * <p>Boots the shared {@link FirestoreEmulator}, flips
 * {@code app.persistence.firestore-enabled=true}, and points the app's
 * {@code Firestore} bean at the emulator via {@code FIRESTORE_EMULATOR_HOST}
 * (already honoured by {@code FirestoreConfig}). Data is cleared before each
 * test. Tagged {@code firestore-emulator} so only the {@code integrationTest}
 * Gradle task runs it.
 *
 * <p>Subclasses that need feature beans normally disabled under the test profile
 * (e.g. medications, nutrition capture) should re-enable just those flags with
 * their own {@code @DynamicPropertySource} / {@code @TestPropertySource}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorCondition.class)
public abstract class AbstractFirestoreIntegrationTest {

    @Autowired
    protected Firestore firestore;

    @DynamicPropertySource
    static void emulatorProperties(DynamicPropertyRegistry registry) {
        FirestoreEmulator emulator = FirestoreEmulator.ensureStarted();
        registry.add("app.persistence.firestore-enabled", () -> "true");
        registry.add("FIRESTORE_EMULATOR_HOST", emulator::hostPort);
    }

    @BeforeEach
    void clearFirestoreData() {
        FirestoreEmulator.ensureStarted().clearData();
    }
}
