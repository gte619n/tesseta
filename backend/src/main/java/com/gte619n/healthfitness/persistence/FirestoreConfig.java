package com.gte619n.healthfitness.persistence;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Gated on app.persistence.firestore-enabled (default true) so unit tests
// can turn the real Firestore beans off and provide an in-memory fake.
//
// `databaseId` lets us route deployed environments to a separate Firestore
// database (e.g., "production") while local development hits "(default)".
//
// When FIRESTORE_EMULATOR_HOST is set (UAT / local end-to-end runs, see
// infra/scripts/uat.sh), point the client at the emulator. setEmulatorHost
// also swaps in the emulator's no-op credentials, so the server boots and
// reads/writes Firestore with NO real GCP credentials. Production is
// unaffected: the env var is never set there.
@Configuration
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreConfig {

    @Bean
    Firestore firestore(
        @Value("${app.gcp.project-id}") String projectId,
        @Value("${app.gcp.firestore-database-id:(default)}") String databaseId,
        @Value("${FIRESTORE_EMULATOR_HOST:}") String emulatorHost
    ) {
        FirestoreOptions.Builder options = FirestoreOptions.newBuilder()
            .setProjectId(projectId)
            .setDatabaseId(databaseId);
        if (emulatorHost != null && !emulatorHost.isBlank()) {
            options.setEmulatorHost(emulatorHost);
        }
        return options.build().getService();
    }
}
