package com.gte619n.healthfitness.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gte619n.healthfitness.core.audit.AuditEntry;
import com.gte619n.healthfitness.core.audit.AuditLogRepository;
import com.gte619n.healthfitness.core.audit.ImpersonationService;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRole;
import com.gte619n.healthfitness.core.user.UserStatus;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** IMPL-MULTIUSER-01 P1.6 (D17) — read-only enforcement, audit, grant resolution. */
class ImpersonationControllerTest {

    private InMemoryUserRepository users;
    private FakeAudit audit;
    private ImpersonationService impersonation;
    private ImpersonationController controller;

    private static final class FakeAudit implements AuditLogRepository {
        final List<AuditEntry> rows = new ArrayList<>();
        @Override public void append(AuditEntry entry) { rows.add(entry); }
        @Override public List<AuditEntry> recent(int limit) {
            List<AuditEntry> copy = new ArrayList<>(rows);
            java.util.Collections.reverse(copy);
            return copy.stream().limit(limit).toList();
        }
    }

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        audit = new FakeAudit();
        impersonation = new ImpersonationService(audit);
        CurrentUserProvider admin = () -> new CurrentUser("admin-1", "a@b.c", "Admin", null);
        controller = new ImpersonationController(impersonation, audit, users, admin);

        users.save(new User("user-2", "u2@example.com", "User Two", null, null,
            Instant.now(), Instant.now(), null, null, null, List.of(),
            java.util.EnumSet.of(UserRole.USER), UserStatus.ACTIVE));
    }

    @Test
    void startThenViewReadsTargetAndWritesAuditRows() {
        var start = controller.start(new ImpersonationController.StartRequest("user-2"));

        ImpersonationController.ImpersonatedUserView view =
            controller.viewUser("user-2", start.token());

        assertThat(view.email()).isEqualTo("u2@example.com");
        assertThat(audit.rows).extracting(AuditEntry::action)
            .containsExactly("IMPERSONATION_START", "IMPERSONATION_READ");
    }

    @Test
    void viewWithoutAValidTokenIsRejected() {
        assertThatThrownBy(() -> controller.viewUser("user-2", "bogus"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void writeUnderImpersonationIsRejected() {
        var start = controller.start(new ImpersonationController.StartRequest("user-2"));

        // A mutating request carrying an impersonation token must be rejected.
        assertThatThrownBy(() -> controller.writeProbe(start.token()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("not permitted under read-only impersonation");
    }
}
