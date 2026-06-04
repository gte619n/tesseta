package com.gte619n.healthfitness.app.workoutimport;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gte619n.healthfitness.core.workoutimport.FutureWorkouts;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deserializes {@code future_workouts.json} (snake_case) into the camelCase
 * {@link FutureWorkouts} core record. Lives in {@code app} because {@code core}
 * carries no Jackson dependency. Records bind by parameter name via Spring
 * Boot's parameter-names module.
 */
public final class FutureWorkoutsParser {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // The export contains NaN weight values for some sets — accept them
        // (the importer then sanitizes NaN → null).
        .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
        .build();

    private FutureWorkoutsParser() {}

    public static FutureWorkouts parse(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, FutureWorkouts.class);
        }
    }

    public static FutureWorkouts parse(InputStream in) throws Exception {
        return MAPPER.readValue(in, FutureWorkouts.class);
    }
}
