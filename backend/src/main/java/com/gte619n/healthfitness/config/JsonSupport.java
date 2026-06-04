package com.gte619n.healthfitness.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared, immutable {@link ObjectMapper}s for manual (de)serialization outside
 * the Spring MVC message converters. {@code ObjectMapper} is thread-safe once
 * configured, so these are safe to share as static singletons — and it
 * collapses ~14 hand-rolled copies that had drifted into three near-identical
 * shapes.
 *
 * <p>Two flavors cover every prior call site except {@code FutureWorkoutsParser}
 * (whose SNAKE_CASE + non-numeric-number config is genuinely one-off):
 * <ul>
 *   <li>{@link #WEB} — what the SSE controllers used: java.time support, ISO
 *       dates (not timestamps). Mirrors the Spring-managed mapper's date
 *       handling for hand-rolled SSE payloads.</li>
 *   <li>{@link #LENIENT} — what the {@code integrations} response parsers used:
 *       tolerate unknown properties (external/LLM JSON drifts) and omit nulls.
 *       Includes the JavaTimeModule so the dexa/bloodtest parsers keep their
 *       java.time handling; the others never read java.time, so it's a no-op
 *       for them. (NON_NULL only affects serialization, and every parser is
 *       read-only, so it too is a behavioral no-op — kept to match the prior
 *       richest config exactly.)</li>
 * </ul>
 */
public final class JsonSupport {

    private JsonSupport() {}

    public static final ObjectMapper WEB = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    public static final ObjectMapper LENIENT = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .serializationInclusion(JsonInclude.Include.NON_NULL)
        .build();
}
