package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

// Locks the subscriber registration body to the live API schema
// (endpointAuthorization + subscriberConfigs), guarding against a regression
// to the earlier guessed shape (authorization + flat dataTypes) that the
// Health API silently rejects.
class GoogleHealthSubscriberClientTest {

    private final GoogleHealthSubscriberClient client =
        new GoogleHealthSubscriberClient("https://health.googleapis.com/v4");

    @Test
    void buildsRequestBodyInLiveApiSchema() {
        JsonNode body = client.requestBody(
            "https://api.tesseta.com/api/webhooks/google-health",
            "Bearer s3cr3t",
            List.of("weight", "body-fat", "steps", "daily-resting-heart-rate"));

        assertThat(body.get("endpointUri").asText())
            .isEqualTo("https://api.tesseta.com/api/webhooks/google-health");

        // Correct nesting: endpointAuthorization.secret, not a flat
        // "authorization" object.
        assertThat(body.get("endpointAuthorization").get("secret").asText())
            .isEqualTo("Bearer s3cr3t");
        assertThat(body.has("authorization")).isFalse();

        // Data types live under subscriberConfigs[].dataTypes with an
        // AUTOMATIC create policy — not a top-level "dataTypes" array.
        assertThat(body.has("dataTypes")).isFalse();
        JsonNode config = body.get("subscriberConfigs").get(0);
        assertThat(config.get("subscriptionCreatePolicy").asText()).isEqualTo("AUTOMATIC");
        assertThat(config.get("dataTypes").toString())
            .contains("weight", "body-fat", "steps", "daily-resting-heart-rate");
    }
}
