package com.gte619n.healthfitness.core.audit;

import java.time.Instant;

/**
 * IMPL-MULTIUSER-01 P1.6 (D17) — one row of the support/impersonation audit log.
 * Every read an admin performs <em>as</em> another user writes exactly one of
 * these to {@code auditLog/{id}}, so support access to user PHI is fully
 * traceable.
 *
 * @param id            unique id of this audit row
 * @param adminId       the acting admin's userId
 * @param targetUserId  the user whose data was accessed
 * @param action        short verb, e.g. {@code "IMPERSONATION_READ"}, {@code "IMPERSONATION_START"}
 * @param resourcePath  the resource read, e.g. {@code "/api/admin/impersonation/view/users/{id}"}
 * @param at            when the access happened
 */
public record AuditEntry(
    String id,
    String adminId,
    String targetUserId,
    String action,
    String resourcePath,
    Instant at
) {}
