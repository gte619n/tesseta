package com.gte619n.healthfitness.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** IMPL-MULTIUSER-01 P1.6 (D17) — grant lifecycle, audit rows, and expiry. */
class ImpersonationServiceTest {

    private static final class FakeAudit implements AuditLogRepository {
        final List<AuditEntry> rows = new ArrayList<>();
        @Override public void append(AuditEntry entry) { rows.add(entry); }
        @Override public List<AuditEntry> recent(int limit) {
            List<AuditEntry> copy = new ArrayList<>(rows);
            java.util.Collections.reverse(copy);
            return copy.stream().limit(limit).toList();
        }
    }

    @Test
    void startWritesAnAuditRowAndResolvesForRead() {
        FakeAudit audit = new FakeAudit();
        ImpersonationService svc = new ImpersonationService(audit);

        ImpersonationService.Grant grant = svc.start("admin-1", "user-2");
        Optional<String> target = svc.resolveForRead(grant.token(), "/view/users/user-2");

        assertThat(target).contains("user-2");
        // One START row + one READ row, both attributed to admin→target.
        assertThat(audit.rows).extracting(AuditEntry::action)
            .containsExactly("IMPERSONATION_START", "IMPERSONATION_READ");
        assertThat(audit.rows).allMatch(
            r -> "admin-1".equals(r.adminId()) && "user-2".equals(r.targetUserId()));
        assertThat(audit.rows.get(1).resourcePath()).isEqualTo("/view/users/user-2");
    }

    @Test
    void expiredGrantResolvesToEmptyAndWritesNoReadRow() {
        FakeAudit audit = new FakeAudit();
        ImpersonationService svc = new ImpersonationService(audit);

        Instant t0 = Instant.parse("2026-10-02T00:00:00Z");
        ImpersonationService.Grant grant =
            svc.start("admin-1", "user-2", Duration.ofMinutes(15), t0);

        // 16 minutes later the grant is expired.
        Optional<String> target =
            svc.resolveForRead(grant.token(), "/view/users/user-2", t0.plus(Duration.ofMinutes(16)));

        assertThat(target).isEmpty();
        // Only the START row was written — no READ row for an expired grant.
        assertThat(audit.rows).extracting(AuditEntry::action).containsExactly("IMPERSONATION_START");
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        ImpersonationService svc = new ImpersonationService(new FakeAudit());
        assertThat(svc.resolveForRead("nope", "/x")).isEmpty();
        assertThat(svc.resolveForRead(null, "/x")).isEmpty();
    }
}
