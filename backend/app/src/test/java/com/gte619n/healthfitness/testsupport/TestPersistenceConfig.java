package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.core.user.UserRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// Replaces the Firestore-backed persistence beans with in-memory fakes for
// unit tests that don't need real Firestore. Wired by @Import on each test
// class that needs it.
@TestConfiguration
public class TestPersistenceConfig {

    @Bean
    UserRepository userRepository() {
        return new InMemoryUserRepository();
    }

    @Bean
    BodyCompositionRepository bodyCompositionRepository() {
        return new InMemoryBodyCompositionRepository();
    }

    @Bean
    BloodReadingRepository bloodReadingRepository() {
        return new InMemoryBloodReadingRepository();
    }

    @Bean
    LocationRepository locationRepository() {
        return new InMemoryLocationRepository();
    }
}
