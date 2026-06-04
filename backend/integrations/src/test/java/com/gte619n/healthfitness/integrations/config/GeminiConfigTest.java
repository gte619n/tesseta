package com.gte619n.healthfitness.integrations.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.Client;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the conditional wiring of the shared Gemini {@link Client} bean:
 * present when an API key is configured (the production path), absent otherwise
 * (the test/unconfigured path that lets the app context load without a key).
 */
class GeminiConfigTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(GeminiConfig.class);

    @Test
    void clientBeanPresentWhenApiKeyConfigured() {
        runner.withPropertyValues("app.gemini.api-key=test-gemini-key")
            .run(context -> assertThat(context).hasSingleBean(Client.class));
    }

    @Test
    void clientBeanAbsentWhenApiKeyMissing() {
        runner.run(context -> assertThat(context).doesNotHaveBean(Client.class));
    }

    @Test
    void clientBeanAbsentWhenApiKeyBlank() {
        runner.withPropertyValues("app.gemini.api-key=")
            .run(context -> assertThat(context).doesNotHaveBean(Client.class));
    }
}
