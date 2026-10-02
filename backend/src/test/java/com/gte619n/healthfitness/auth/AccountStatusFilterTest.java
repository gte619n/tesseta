package com.gte619n.healthfitness.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.AdminBootstrap;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRole;
import com.gte619n.healthfitness.core.user.UserStatus;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * IMPL-MULTIUSER-01 P1.4: the request-boundary lockout. ACTIVE passes; pending/
 * suspended/disabled get 403 with the stable problem type; a bootstrap admin is
 * never locked out; an unresolvable/absent user fails open.
 */
class AccountStatusFilterTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private AccountStatusFilter filter(String adminEmailsCsv) {
        CurrentUserProvider provider = () -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null ? null : (CurrentUser) auth.getPrincipal();
        };
        return new AccountStatusFilter(provider, users, new AdminBootstrap(adminEmailsCsv));
    }

    private void authenticate(CurrentUser user) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, "n/a", AuthorityUtils.NO_AUTHORITIES));
    }

    private int run(AccountStatusFilter filter) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.setRequestURI("/api/me");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(req, res, chain);
        boolean passed = Mockito.mockingDetails(chain).getInvocations().stream()
            .anyMatch(i -> i.getMethod().getName().equals("doFilter"));
        return passed ? 200 : res.getStatus();
    }

    private void seed(String id, String email, UserStatus status, UserRole... roles) {
        users.save(new User(id, email, email, null, null, Instant.now(), Instant.now(),
            null, null, null, List.of(),
            roles.length == 0 ? EnumSet.of(UserRole.USER) : EnumSet.copyOf(List.of(roles)),
            status));
    }

    @Test
    void activeUserPasses() throws Exception {
        seed("u1", "a@example.com", UserStatus.ACTIVE);
        authenticate(new CurrentUser("u1", "a@example.com", "A", null));
        assertThat(run(filter(""))).isEqualTo(200);
    }

    @Test
    void suspendedUserIsBlocked() throws Exception {
        seed("u2", "b@example.com", UserStatus.SUSPENDED);
        authenticate(new CurrentUser("u2", "b@example.com", "B", null));
        assertThat(run(filter(""))).isEqualTo(403);
    }

    @Test
    void pendingUserIsBlocked() throws Exception {
        seed("u3", "c@example.com", UserStatus.PENDING_APPROVAL);
        authenticate(new CurrentUser("u3", "c@example.com", "C", null));
        assertThat(run(filter(""))).isEqualTo(403);
    }

    @Test
    void disabledUserIsBlocked() throws Exception {
        seed("u4", "d@example.com", UserStatus.DISABLED);
        authenticate(new CurrentUser("u4", "d@example.com", "D", null));
        assertThat(run(filter(""))).isEqualTo(403);
    }

    @Test
    void bootstrapAdminIsNeverLockedOutEvenIfSuspended() throws Exception {
        seed("owner", "owner@example.com", UserStatus.SUSPENDED, UserRole.USER, UserRole.ADMIN);
        authenticate(new CurrentUser("owner", "owner@example.com", "Owner", null));
        assertThat(run(filter("owner@example.com"))).isEqualTo(200);
    }

    @Test
    void missingUserDocFailsOpen() throws Exception {
        authenticate(new CurrentUser("ghost", "ghost@example.com", "Ghost", null));
        assertThat(run(filter(""))).isEqualTo(200);
    }

    @Test
    void unauthenticatedRequestPassesThrough() throws Exception {
        assertThat(run(filter(""))).isEqualTo(200);
    }
}
