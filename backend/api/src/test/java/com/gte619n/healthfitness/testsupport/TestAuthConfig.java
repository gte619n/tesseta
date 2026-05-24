package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.context.annotation.RequestScope;

// Provides a stub CurrentUserProvider for tests.
// Returns user-123 by default, can be overridden by request-scoped bean.
@TestConfiguration
public class TestAuthConfig {

    @Bean
    @Primary
    @RequestScope
    CurrentUserProvider currentUserProvider() {
        return () -> new CurrentUser("user-123", "test@example.com", "Test User");
    }
}
