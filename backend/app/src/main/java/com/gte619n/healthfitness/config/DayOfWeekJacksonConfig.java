package com.gte619n.healthfitness.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The gym-hours map is keyed by {@link DayOfWeek}. The web/Android clients use
 * lowercase JSON keys ("mon", "tue", ...), but the core enum constants are
 * uppercase, so default Jackson handling fails to deserialize the map key
 * (HTTP 500). Core is a pure-Java module and must not depend on Jackson, so the
 * lowercase mapping is registered here in the Spring-aware app module instead —
 * for both reading (key deserializer) and writing (key serializer), keeping the
 * wire contract lowercase in both directions.
 *
 * <p>Separately, the <em>medication</em> {@link com.gte619n.healthfitness.core.medication.DayOfWeek}
 * is sent as a list of enum <em>values</em> for a weekly schedule's
 * {@code specificDays}. The Android client serializes those lowercase
 * ("mon".."sun") via its shared Moshi adapter, which the default uppercase-only
 * Jackson enum deserializer rejected with a 400. Register a case-insensitive
 * value deserializer so both clients round-trip; serialization stays the
 * uppercase enum name (the web client keys its labels on the uppercase form).
 */
@Configuration
public class DayOfWeekJacksonConfig {

    @Bean
    public SimpleModule dayOfWeekModule() {
        SimpleModule module = new SimpleModule("DayOfWeekLowercaseKeys");
        module.addKeyDeserializer(DayOfWeek.class, new DayOfWeekKeyDeserializer());
        module.addKeySerializer(DayOfWeek.class, new DayOfWeekKeySerializer());
        module.addDeserializer(
            com.gte619n.healthfitness.core.medication.DayOfWeek.class,
            new MedicationDayOfWeekDeserializer());
        return module;
    }

    // Accepts "sun" or "SUN" (any case) for medication weekly-schedule days.
    static final class MedicationDayOfWeekDeserializer
        extends JsonDeserializer<com.gte619n.healthfitness.core.medication.DayOfWeek> {
        @Override
        public com.gte619n.healthfitness.core.medication.DayOfWeek deserialize(
            JsonParser p, DeserializationContext ctxt) throws IOException {
            String raw = p.getValueAsString();
            return raw == null
                ? null
                : com.gte619n.healthfitness.core.medication.DayOfWeek.valueOf(raw.trim().toUpperCase());
        }
    }

    static final class DayOfWeekKeyDeserializer extends KeyDeserializer {
        @Override
        public Object deserializeKey(String key, com.fasterxml.jackson.databind.DeserializationContext ctxt) {
            return DayOfWeek.valueOf(key.toUpperCase());
        }
    }

    static final class DayOfWeekKeySerializer extends JsonSerializer<DayOfWeek> {
        @Override
        public void serialize(DayOfWeek value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
            gen.writeFieldName(value.name().toLowerCase());
        }
    }
}
