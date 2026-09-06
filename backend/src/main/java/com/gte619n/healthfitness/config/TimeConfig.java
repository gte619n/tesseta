package com.gte619n.healthfitness.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable {@link Clock} so time-dependent services (the progression
 * engine, IMPL-PROG-01) can be driven by a fixed clock in tests. Defaults to
 * system UTC in production.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
