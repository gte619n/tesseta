package com.gte619n.healthfitness.core.audit;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * IMPL-MULTIUSER-01 P1.6 (D17) — mints and resolves short-TTL, read-only
 * impersonation grants and writes the audit trail.
 *
 * <p><b>Mechanism (decision DL-P1.6):</b> an admin "starts" an impersonation of a
 * target user and receives an opaque, single-purpose token bound to
 * {@code {adminId, targetUserId, expiresAt}}. The admin passes that token on each
 * proxied GET read; the server resolves it to the target userId, enforces
 * read-only + expiry, and writes one {@link AuditEntry} per access. This is the
 * simplest correct mechanism that keeps the grant time-boxed and never lets the
 * admin act as the user (no write path exists under a grant).
 *
 * <p>Grants are held in-memory (process-local). For the single Cloud Run
 * deployable this is adequate; a multi-instance deployment would move the grant
 * store to Firestore/Redis (noted in the decision log). The audit log itself IS
 * durable (Firestore), so the compliance record never depends on this map.
 */
@Service
public class ImpersonationService {

    /** Impersonation grants are short — a support session, not a standing right. */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

    private final AuditLogRepository audit;
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public ImpersonationService(AuditLogRepository audit) {
        this.audit = audit;
    }

    /** A live impersonation grant. */
    public record Grant(String token, String adminId, String targetUserId, Instant expiresAt) {
        public boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * Start a time-boxed read-only impersonation of {@code targetUserId} by
     * {@code adminId}. Writes an {@code IMPERSONATION_START} audit row and returns
     * the grant (the token is what the admin presents on subsequent reads).
     */
    public Grant start(String adminId, String targetUserId) {
        return start(adminId, targetUserId, DEFAULT_TTL, Instant.now());
    }

    /** Overload with an explicit TTL and clock — for deterministic expiry tests. */
    public Grant start(String adminId, String targetUserId, Duration ttl, Instant now) {
        String token = UUID.randomUUID().toString();
        Grant grant = new Grant(token, adminId, targetUserId, now.plus(ttl));
        grants.put(token, grant);
        audit.append(new AuditEntry(UUID.randomUUID().toString(), adminId, targetUserId,
            "IMPERSONATION_START", "impersonation", now));
        return grant;
    }

    /**
     * Resolve a token to its target userId for a READ, enforcing expiry, and write
     * an {@code IMPERSONATION_READ} audit row for {@code resourcePath}. Returns the
     * target userId, or empty when the token is unknown/expired.
     */
    public Optional<String> resolveForRead(String token, String resourcePath) {
        return resolveForRead(token, resourcePath, Instant.now());
    }

    /** Overload with an explicit clock — for deterministic expiry tests. */
    public Optional<String> resolveForRead(String token, String resourcePath, Instant now) {
        if (token == null) {
            return Optional.empty();
        }
        Grant grant = grants.get(token);
        if (grant == null) {
            return Optional.empty();
        }
        if (grant.isExpired(now)) {
            grants.remove(token);
            return Optional.empty();
        }
        audit.append(new AuditEntry(UUID.randomUUID().toString(), grant.adminId(),
            grant.targetUserId(), "IMPERSONATION_READ", resourcePath, now));
        return Optional.of(grant.targetUserId());
    }
}
