package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.sync.WriteResult;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * De-risks the {@link WriteResult} envelope (#11): the wrapped body's fields must
 * flatten to top-level JSON via {@code @JsonUnwrapped} while a sibling
 * {@code lastUpdate} appears as an ISO-8601 string — matching the delta feed's
 * {@code lastUpdate} encoding — using Spring Boot's auto-configured Jackson (the
 * exact {@link ObjectMapper} the controllers serialize with).
 */
class WriteResultSerializationTest {

    record Body(String readingId, String marker, double value) {}

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(JacksonAutoConfiguration.class));

    @Test
    void bodyFieldsFlattenAndLastUpdateIsSiblingIso() {
        contextRunner.run(ctx -> {
            ObjectMapper json = ctx.getBean(ObjectMapper.class);
            Instant ts = Instant.parse("2026-06-02T18:04:11.482Z");
            WriteResult<Body> result = WriteResult.of(new Body("r1", "HDL", 55.0), ts);

            JsonNode node = json.readTree(json.writeValueAsString(result));

            // Body fields are top-level (unwrapped), not nested under "body".
            assertThat(node.has("body")).isFalse();
            assertThat(node.get("readingId").asText()).isEqualTo("r1");
            assertThat(node.get("marker").asText()).isEqualTo("HDL");
            assertThat(node.get("value").asDouble()).isEqualTo(55.0);

            // lastUpdate is a sibling, ISO-8601 (not an epoch number).
            assertThat(node.get("lastUpdate").asText()).isEqualTo("2026-06-02T18:04:11.482Z");
        });
    }
}
