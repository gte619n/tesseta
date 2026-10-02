package com.gte619n.healthfitness.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.access.EmailAllowlistEntry;
import com.gte619n.healthfitness.core.access.EmailAllowlistRepository;
import com.gte619n.healthfitness.core.access.EmailAllowlistService;
import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
import com.gte619n.healthfitness.core.user.AdminBootstrap;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRole;
import com.gte619n.healthfitness.core.user.UserService;
import com.gte619n.healthfitness.core.user.UserStatus;
import com.gte619n.healthfitness.testsupport.InMemoryGoalRepository;
import com.gte619n.healthfitness.testsupport.InMemoryRefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P1.7 (D13) — export completeness + tenant isolation, scheduling. */
class DataLifecycleControllerTest {

    private InMemoryUserRepository users;
    private InMemoryGoalRepository goals;
    private UserService userService;
    private DataLifecycleController controller;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        goals = new InMemoryGoalRepository();
        EmailAllowlistService allowlist = new EmailAllowlistService(new InMemoryAllowlist());
        RefreshTokenStore refreshTokens = new InMemoryRefreshTokenStore();
        userService = new UserService(users, allowlist, new AdminBootstrap(""), refreshTokens);
        controller = new DataLifecycleController(users, userService, goals);

        users.save(new User("target", "t@example.com", "Target", null, null,
            Instant.now(), Instant.now(), null, null, null, List.of(),
            java.util.EnumSet.of(UserRole.USER), UserStatus.ACTIVE));
        users.save(new User("other", "o@example.com", "Other", null, null,
            Instant.now(), Instant.now(), null, null, null, List.of(),
            java.util.EnumSet.of(UserRole.USER), UserStatus.ACTIVE));

        goals.save(goal("target", "g-target", "Mine"));
        goals.save(goal("other", "g-other", "NotMine"));
    }

    private static Goal goal(String userId, String goalId, String title) {
        return new Goal(userId, goalId, title, "d", GoalDomain.STRENGTH, GoalStatus.ACTIVE,
            LocalDate.now(), LocalDate.now().plusDays(30), Instant.now(), Instant.now(),
            null, List.of(), GoalSource.MANUAL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void exportContainsTargetUserDataAndNotAnotherUsers() {
        Map<String, Object> bundle = controller.export("target");

        assertThat(bundle.get("userId")).isEqualTo("target");
        Map<String, Object> profile = (Map<String, Object>) bundle.get("profile");
        assertThat(profile.get("email")).isEqualTo("t@example.com");

        List<Goal> exportedGoals = (List<Goal>) bundle.get("goals");
        assertThat(exportedGoals).extracting(Goal::goalId).containsExactly("g-target");
        // Tenant isolation: the other user's goal must NOT appear.
        assertThat(exportedGoals).noneMatch(g -> "other".equals(g.userId()));
    }

    @Test
    void scheduleDeletionDisablesAndStampsADate() {
        DataLifecycleController.ScheduleDeletionResponse resp =
            controller.scheduleDeletion("target");

        assertThat(resp.status()).isEqualTo("DISABLED");
        assertThat(resp.deletionScheduledAt()).isNotNull();
        User u = users.findById("target").orElseThrow();
        assertThat(u.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(u.deletionScheduledAt()).isNotNull();
    }

    private static final class InMemoryAllowlist implements EmailAllowlistRepository {
        private final java.util.Set<String> allowed = ConcurrentHashMap.newKeySet();
        @Override public boolean contains(String emailLower) { return allowed.contains(emailLower); }
        @Override public void add(EmailAllowlistEntry entry) { allowed.add(entry.emailLower()); }
        @Override public void remove(String emailLower) { allowed.remove(emailLower); }
        @Override public List<EmailAllowlistEntry> list() { return List.of(); }
    }
}
