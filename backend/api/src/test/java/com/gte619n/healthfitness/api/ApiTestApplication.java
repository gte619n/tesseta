package com.gte619n.healthfitness.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstrap config for the {@code api} module's slice tests.
 *
 * The {@code api} module is a plain {@code java-library} with no
 * {@code @SpringBootApplication} of its own — that lives in {@code app}. A
 * {@code @WebMvcTest} walks up the package tree looking for a
 * {@code @SpringBootConfiguration} to anchor the context, and relies on
 * component scanning to find the controller under test; a bare
 * {@code @SpringBootConfiguration} has no {@code @ComponentScan}, so the
 * controller is never registered and every request 404s. {@code @SpringBootApplication}
 * supplies the component scan (rooted at this package), and {@code @WebMvcTest}'s
 * own type filter narrows it back down to just the named controller plus the
 * web infrastructure ({@code @RestControllerAdvice}, converters). Collaborators
 * are supplied per-test as {@code @MockitoBean}s.
 */
@SpringBootApplication
class ApiTestApplication {}
