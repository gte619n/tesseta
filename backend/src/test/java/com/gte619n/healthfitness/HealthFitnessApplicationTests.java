package com.gte619n.healthfitness;

import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class HealthFitnessApplicationTests {
    @Test
    void contextLoads() {}
}
