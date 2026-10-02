package com.gte619n.healthfitness.core.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.core.access.EmailAllowlistEntry;
import com.gte619n.healthfitness.core.access.EmailAllowlistRepository;
import com.gte619n.healthfitness.core.access.EmailAllowlistService;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryRefreshTokenStore;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * IMPL-MULTIUSER-01 P1.1–P1.5: provisioning gate (allowlist/bootstrap), status
 * transitions with the DISABLED/SUSPENDED token burn, and last-admin protection.
 */
class UserServiceTest {

    private final InMemoryUserRepository users = new InMemoryUserRepository();
    private final InMemoryAllowlist allowlistRepo = new InMemoryAllowlist();
    private final EmailAllowlistService allowlist = new EmailAllowlistService(allowlistRepo);
    private final RecordingRefreshTokenStore refreshTokens = new RecordingRefreshTokenStore();

    private UserService service(String adminEmailsCsv) {
        return new UserService(users, allowlist, new AdminBootstrap(adminEmailsCsv), refreshTokens);
    }

    private static CurrentUser caller(String id, String email) {
        return new CurrentUser(id, email, email, null);
    }

    @Test
    void allowlistedUserIsProvisionedActive() {
        allowlist.allow("ada@example.com", "admin");
        service("").provisionIfAbsent(caller("u1", "ada@example.com"));

        User u = users.findById("u1").orElseThrow();
        assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.roles()).containsExactly(UserRole.USER);
    }

    @Test
    void nonAllowlistedUserIsProvisionedPending() {
        service("").provisionIfAbsent(caller("u2", "stranger@example.com"));

        assertThat(users.findById("u2").orElseThrow().status())
            .isEqualTo(UserStatus.PENDING_APPROVAL);
    }

    @Test
    void bootstrapAdminIsProvisionedActiveWithAdminRole() {
        service("owner@example.com").provisionIfAbsent(caller("u3", "owner@example.com"));

        User u = users.findById("u3").orElseThrow();
        assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.roles()).contains(UserRole.ADMIN);
    }

    @Test
    void approveFlipsPendingToActive() {
        service("").provisionIfAbsent(caller("u4", "stranger@example.com"));
        service("").approve("u4");
        assertThat(users.findById("u4").orElseThrow().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void suspendBurnsRefreshTokens() {
        seedActive("u5", "x@example.com", UserRole.USER);
        service("").setStatus("u5", UserStatus.SUSPENDED);

        assertThat(users.findById("u5").orElseThrow().status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(refreshTokens.burned).contains("u5");
    }

    @Test
    void disableBurnsRefreshTokens() {
        seedActive("u6", "y@example.com", UserRole.USER);
        service("").setStatus("u6", UserStatus.DISABLED);
        assertThat(refreshTokens.burned).contains("u6");
    }

    @Test
    void cannotDeactivateTheLastAdminWhenNoEnvBootstrap() {
        seedActive("admin1", "a1@example.com", UserRole.ADMIN);
        assertThatThrownBy(() -> service("").setStatus("admin1", UserStatus.SUSPENDED))
            .isInstanceOf(UserService.LastAdminException.class);
    }

    @Test
    void canDeactivateAnAdminWhenAnotherActiveAdminExists() {
        seedActive("admin1", "a1@example.com", UserRole.ADMIN);
        seedActive("admin2", "a2@example.com", UserRole.ADMIN);
        service("").setStatus("admin1", UserStatus.SUSPENDED);
        assertThat(users.findById("admin1").orElseThrow().status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void envBootstrapMakesLastAdminProtectionAFallback() {
        // With an env admin present, you can always get back in, so the last
        // data-admin is not special-cased.
        seedActive("admin1", "a1@example.com", UserRole.ADMIN);
        service("owner@example.com").setStatus("admin1", UserStatus.SUSPENDED);
        assertThat(users.findById("admin1").orElseThrow().status()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    void revokeAdminFromLastAdminIsBlocked() {
        seedActive("admin1", "a1@example.com", UserRole.ADMIN);
        assertThatThrownBy(() -> service("").revokeRole("admin1", UserRole.ADMIN))
            .isInstanceOf(UserService.LastAdminException.class);
    }

    @Test
    void grantAndRevokeRoleRoundTrips() {
        seedActive("u7", "z@example.com", UserRole.USER);
        service("").grantRole("u7", UserRole.ADMIN);
        assertThat(users.findById("u7").orElseThrow().isAdmin()).isTrue();
        // another admin so revoke is allowed
        seedActive("u8", "z2@example.com", UserRole.ADMIN);
        service("").revokeRole("u7", UserRole.ADMIN);
        assertThat(users.findById("u7").orElseThrow().isAdmin()).isFalse();
    }

    // ---- P1.7 export-then-delete offboarding (D13) ----

    @Test
    void scheduleDeletionDisablesAndStampsThirtyDaysOutAndBurnsTokens() {
        seedActive("off1", "off1@example.com", UserRole.USER);
        Instant before = Instant.now();

        Instant scheduledAt = service("").scheduleDeletion("off1");

        User u = users.findById("off1").orElseThrow();
        assertThat(u.status()).isEqualTo(UserStatus.DISABLED);
        assertThat(u.deletionScheduledAt()).isNotNull();
        // ~30 days out (window arithmetic).
        assertThat(u.deletionScheduledAt())
            .isAfter(before.plus(java.time.Duration.ofDays(29)))
            .isBefore(before.plus(java.time.Duration.ofDays(31)));
        assertThat(scheduledAt).isEqualTo(u.deletionScheduledAt());
        assertThat(refreshTokens.burned).contains("off1");
    }

    @Test
    void reactivatingWithinWindowClearsTheSchedule() {
        seedActive("off2", "off2@example.com", UserRole.USER);
        service("").scheduleDeletion("off2");
        assertThat(users.findById("off2").orElseThrow().deletionScheduledAt()).isNotNull();

        service("").setStatus("off2", UserStatus.ACTIVE);

        User u = users.findById("off2").orElseThrow();
        assertThat(u.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.deletionScheduledAt()).isNull();
    }

    @Test
    void purgeDueDeletesOnlyAccountsPastTheWindow() {
        seedActive("due", "due@example.com", UserRole.USER);
        seedActive("fresh", "fresh@example.com", UserRole.USER);
        // "due" was scheduled 31 days ago; "fresh" scheduled just now.
        users.updateDeletionSchedule("due", UserStatus.DISABLED,
            Instant.now().minus(java.time.Duration.ofDays(31)));
        service("").scheduleDeletion("fresh");

        List<String> purged = service("").purgeDue(Instant.now());

        assertThat(purged).containsExactly("due");
        assertThat(users.findById("due")).isEmpty();
        assertThat(users.findById("fresh")).isPresent();
    }

    private void seedActive(String id, String email, UserRole role) {
        users.save(new User(id, email, email, null, null, Instant.now(), Instant.now(),
            null, null, null, List.of(),
            java.util.EnumSet.of(UserRole.USER, role), UserStatus.ACTIVE));
    }

    // ---- fakes ----

    private static final class InMemoryAllowlist implements EmailAllowlistRepository {
        private final java.util.Set<String> allowed = ConcurrentHashMap.newKeySet();
        @Override public boolean contains(String emailLower) { return allowed.contains(emailLower); }
        @Override public void add(EmailAllowlistEntry entry) { allowed.add(entry.emailLower()); }
        @Override public void remove(String emailLower) { allowed.remove(emailLower); }
        @Override public List<EmailAllowlistEntry> list() { return List.of(); }
    }

    private static final class RecordingRefreshTokenStore extends InMemoryRefreshTokenStore {
        final List<String> burned = new ArrayList<>();
        @Override public void revokeAllForUser(String userId) {
            burned.add(userId);
            super.revokeAllForUser(userId);
        }
    }
}
