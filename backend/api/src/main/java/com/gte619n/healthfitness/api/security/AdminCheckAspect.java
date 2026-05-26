package com.gte619n.healthfitness.api.security;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import java.util.Set;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AdminCheckAspect {

    private final CurrentUserProvider currentUserProvider;

    // List of admin email addresses - TODO: move to configuration
    private static final Set<String> ADMIN_EMAILS = Set.of(
        "admin@example.com",
        "evan.ruff@gmail.com"
    );

    public AdminCheckAspect(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Before("@annotation(AdminOnly) || @within(AdminOnly)")
    public void checkAdmin() {
        CurrentUser user = currentUserProvider.get();
        String email = user.email();

        if (email == null || !ADMIN_EMAILS.contains(email)) {
            throw new AccessDeniedException("Admin access required");
        }
    }
}
