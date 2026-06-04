package com.gte619n.healthfitness.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes the Firebase Admin SDK for FCM fan-out (IMPL-AND-20, Phase 2).
 *
 * <p><b>Gated behind {@code app.fcm.enabled=true}</b> so tests and local dev
 * without ADC never construct it — the {@code NoOpFcmSender} handles fan-out in
 * those environments. Default is OFF in {@code application-test.yml}; ON in
 * deployed profiles via {@code application.yml}.
 *
 * <p><b>No service-account JSON key.</b> The SDK is initialized with Application
 * Default Credentials: on Cloud Run, ADC resolves to the runtime SA
 * ({@code health-fitness-runtime@…}, which holds
 * {@code roles/firebasecloudmessaging.admin}) and the project id comes from the
 * metadata server. Locally, ADC resolves to the developer's
 * {@code gcloud auth application-default} credentials (only able to actually
 * send if they can impersonate / hold the FCM role). This honors the project
 * "never commit a service-account JSON key" rule (see the spec Prerequisites).
 */
@Configuration
@ConditionalOnProperty(name = "app.fcm.enabled", havingValue = "true")
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    /**
     * The application-wide {@link FirebaseApp}. Reuses an already-initialized
     * default app if one exists (so repeated context loads in the same JVM, e.g.
     * across tests, don't double-initialize).
     */
    @Bean
    FirebaseApp firebaseApp(@Value("${app.gcp.project-id:}") String projectId) throws IOException {
        if (!FirebaseApp.getApps().isEmpty()) {
            return FirebaseApp.getInstance();
        }
        FirebaseOptions.Builder builder = FirebaseOptions.builder()
            // ADC — no explicit credential; resolves the runtime SA on Cloud Run.
            .setCredentials(com.google.auth.oauth2.GoogleCredentials.getApplicationDefault());
        // Set the project id EXPLICITLY. ADC on Cloud Run does not reliably
        // surface a project id to the Admin SDK, and FCM's sendEachForMulticast
        // then throws "Project ID is required to access messaging service" —
        // every sync fan-out failed, so devices were never woken to re-sync
        // (e.g. a finished meal analysis never reached the phone). We already
        // have the id as app.gcp.project-id (GCP_PROJECT_ID); use it.
        if (projectId != null && !projectId.isBlank()) {
            builder.setProjectId(projectId);
        }
        FirebaseOptions options = builder.build();
        log.info(
            "Initializing FirebaseApp with Application Default Credentials (FCM enabled), projectId={}",
            projectId);
        return FirebaseApp.initializeApp(options);
    }

    @Bean
    FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        return FirebaseMessaging.getInstance(app);
    }
}
