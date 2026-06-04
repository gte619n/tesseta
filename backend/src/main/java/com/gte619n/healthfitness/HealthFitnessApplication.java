package com.gte619n.healthfitness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.gte619n.healthfitness")
public class HealthFitnessApplication {
    public static void main(String[] args) {
        SpringApplication.run(HealthFitnessApplication.class, args);
    }
}
