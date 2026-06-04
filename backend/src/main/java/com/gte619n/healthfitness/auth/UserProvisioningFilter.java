package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// On every authenticated request, ensures a users/{sub} document exists.
// Runs after the auth filters have populated the SecurityContext.
public class UserProvisioningFilter extends OncePerRequestFilter {
    private final UserService users;
    private final CurrentUserProvider currentUserProvider;

    public UserProvisioningFilter(UserService users, CurrentUserProvider currentUserProvider) {
        this.users = users;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
    ) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            users.provisionIfAbsent(currentUserProvider.get());
        }
        chain.doFilter(request, response);
    }
}
