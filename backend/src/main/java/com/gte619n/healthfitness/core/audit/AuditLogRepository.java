package com.gte619n.healthfitness.core.audit;

import java.util.List;

/**
 * IMPL-MULTIUSER-01 P1.6 — port for the append-only support audit log
 * ({@code auditLog/{id}}). Writes a row per admin access; reads expose the most
 * recent rows to the admin console.
 */
public interface AuditLogRepository {

    /** Append one audit row. */
    void append(AuditEntry entry);

    /** Most-recent audit rows, newest first, up to {@code limit}. */
    List<AuditEntry> recent(int limit);
}
