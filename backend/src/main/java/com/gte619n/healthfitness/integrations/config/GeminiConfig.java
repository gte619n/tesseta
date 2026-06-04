package com.gte619n.healthfitness.integrations.config;

import com.google.genai.Client;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single shared google-genai {@link Client}.
 *
 * <p>Every Gemini service previously built its own
 * {@code Client.builder().apiKey(...)} from a per-module property that all
 * resolved to the same {@code GEMINI_API_KEY}. The client is thread-safe and
 * reusable, so one shared bean replaces those constructions.
 *
 * <p><b>Fail-fast, conditional:</b> the bean is created only when the key is
 * present. In production the key is always set (Secret Manager &rarr; env), so
 * the bean exists and the Gemini services wire. If the key is missing while
 * those services are enabled, the bean is absent and the context fails fast with
 * an unsatisfied {@code Client} dependency — the same "won't start without a
 * key" behavior the services had when they threw in their constructors. In tests
 * the key is unset, so the bean is absent; the Gemini services are themselves
 * feature-flagged off there.
 *
 * <p>The two equipment Gemini services intentionally keep their own client: they
 * wire in test contexts (EquipmentImageService is unconditional;
 * EquipmentParserService uses a distinct parser key set to a placeholder in
 * tests), where this shared bean is deliberately absent.
 */
@Configuration
public class GeminiConfig {

    @Bean
    @ConditionalOnExpression("'${app.gemini.api-key:}'.length() > 0")
    public Client geminiClient(@Value("${app.gemini.api-key}") String apiKey) {
        return Client.builder().apiKey(apiKey).build();
    }
}
