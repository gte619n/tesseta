package com.gte619n.healthfitness.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gte619n.healthfitness.core.platform.AuthorizationCodeStore;
import com.gte619n.healthfitness.core.platform.OAuthClientStore;
import com.gte619n.healthfitness.core.platform.OAuthGrantStore;
import com.gte619n.healthfitness.core.platform.PlatformRefreshTokenStore;
import com.gte619n.healthfitness.core.platform.WebhookCheckpointStore;
import com.gte619n.healthfitness.core.platform.WebhookSubscriptionStore;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

// Publishes and pins the /v1 OpenAPI contract (ADR-0020, D16). Boots the full app
// context with the platform ON (the `test` profile keeps it off) so springdoc
// generates the real spec from the annotated controllers + PlatformOpenApiConfig,
// fetches the versioned YAML, and asserts it byte-for-byte against the committed
// artifact that the marketing site's Redoc page and the CI breaking-change gate
// both read. Any un-regenerated change to the public surface fails here — inside
// the `./gradlew build` that backend-ci already runs — so the doc can never drift
// silently from the code.
//
// To regenerate after an intentional API change:
//   ./gradlew test --tests '*V1OpenApiSnapshotTest' -Dopenapi.update=true
//
// Firestore/GCS/Gemini stay off (test profile); the platform's Firestore-backed
// stores have no in-memory fallback, so they are mocked below purely so the
// context wires — spec generation never calls them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import({TestPersistenceConfig.class, V1OpenApiSnapshotTest.PlatformStoreStubs.class})
@TestPropertySource(properties = {
    "app.platform.enabled=true",
    // Enable medications so V1MedicationsController is part of the published doc.
    // That drags in the first-party drug AI stack (DrugLookupService et al.),
    // which needs the shared google-genai Client; a dummy key makes GeminiConfig
    // build one (never called during spec generation).
    "app.medications.enabled=true",
    "app.gemini.api-key=test-openapi-key",
    "app.platform.allow-ephemeral-key=true",
    // Fixed public metadata so the committed artifact is complete and stable,
    // independent of the deploy-time environment variables.
    "app.platform.public-base-url=https://api.tesseta.com",
    "app.platform.docs-url=https://tesseta.com/api.html"
})
class V1OpenApiSnapshotTest {

    // Repo-root-relative (tests run with the backend module as the working dir).
    private static final Path SPEC_FILE =
        Path.of("..", "website", "public", "api", "tesseta-platform-v1.yaml");

    @Autowired
    private MockMvc mvc;

    @Test
    void openApiSpecMatchesCommittedArtifact() throws Exception {
        // Read raw bytes as UTF-8: springdoc's YAML response doesn't set a charset,
        // so getContentAsString() would default to ISO-8859-1 and mangle em-dashes.
        byte[] body = mvc.perform(get("/openapi/v1.yaml"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        String generated = new String(body, StandardCharsets.UTF_8);

        if (Boolean.getBoolean("openapi.update")) {
            Files.createDirectories(SPEC_FILE.getParent());
            Files.writeString(SPEC_FILE, generated);
            return;
        }

        assertThat(Files.exists(SPEC_FILE))
            .as("Committed OpenAPI artifact %s is missing. Generate it with: "
                + "./gradlew test --tests '*V1OpenApiSnapshotTest' -Dopenapi.update=true",
                SPEC_FILE)
            .isTrue();

        String committed = Files.readString(SPEC_FILE);
        assertThat(generated)
            .as("The published /v1 OpenAPI spec has drifted from the committed artifact. "
                + "If this change is intentional, regenerate it with: "
                + "./gradlew test --tests '*V1OpenApiSnapshotTest' -Dopenapi.update=true")
            .isEqualTo(committed);
    }

    // Mock beans for the platform's Firestore-backed stores (absent when
    // app.persistence.firestore-enabled=false) so the context can wire with the
    // platform enabled. Never invoked during spec generation.
    @TestConfiguration
    static class PlatformStoreStubs {
        @Bean OAuthClientStore oAuthClientStore() { return mock(OAuthClientStore.class); }
        @Bean OAuthGrantStore oAuthGrantStore() { return mock(OAuthGrantStore.class); }
        @Bean AuthorizationCodeStore authorizationCodeStore() { return mock(AuthorizationCodeStore.class); }
        @Bean PlatformRefreshTokenStore platformRefreshTokenStore() { return mock(PlatformRefreshTokenStore.class); }
        @Bean WebhookCheckpointStore webhookCheckpointStore() { return mock(WebhookCheckpointStore.class); }
        @Bean WebhookSubscriptionStore webhookSubscriptionStore() { return mock(WebhookSubscriptionStore.class); }
    }
}
