package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.audit.AuditEntry;
import com.gte619n.healthfitness.core.audit.AuditLogRepository;
import com.gte619n.healthfitness.core.audit.ImpersonationService;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-MULTIUSER-01 P1.6 (D17) — read-only impersonation + audit log.
 *
 * <p>An admin {@code POST /start}s a time-boxed, read-only impersonation of a
 * target user and receives an opaque token (see {@link ImpersonationService} for
 * the mechanism rationale). The admin then reads the user's data via the proxied
 * GET endpoints, presenting that token in {@code X-Impersonation-Token}; every
 * read resolves the token to the target userId, enforces expiry, and writes an
 * audit row.
 *
 * <p><b>Read-only enforcement:</b> NO write path exists under a grant — the only
 * token-bearing endpoints are GETs. The explicit {@code POST /write-probe} exists
 * to make the invariant testable: a mutating request that carries an
 * impersonation token is rejected 403. The whole controller is {@link AdminOnly},
 * so only admins can reach any of it.
 */
@RestController
@RequestMapping("/api/admin/impersonation")
@AdminOnly
public class ImpersonationController {

    private final ImpersonationService impersonation;
    private final AuditLogRepository audit;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;

    public ImpersonationController(
        ImpersonationService impersonation,
        AuditLogRepository audit,
        UserRepository users,
        CurrentUserProvider currentUser
    ) {
        this.impersonation = impersonation;
        this.audit = audit;
        this.users = users;
        this.currentUser = currentUser;
    }

    @PostMapping("/start")
    public StartResponse start(@RequestBody StartRequest body) {
        if (body == null || body.targetUserId() == null || body.targetUserId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "targetUserId required");
        }
        if (users.findById(body.targetUserId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        ImpersonationService.Grant grant =
            impersonation.start(currentUser.get().userId(), body.targetUserId());
        return new StartResponse(grant.token(), grant.targetUserId(), grant.expiresAt());
    }

    /** Read the impersonated user's profile under a valid grant (writes an audit row). */
    @GetMapping("/view/users/{userId}")
    public ImpersonatedUserView viewUser(
        @PathVariable String userId,
        @RequestHeader(name = "X-Impersonation-Token", required = false) String token
    ) {
        String target = impersonation
            .resolveForRead(token, "/api/admin/impersonation/view/users/" + userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.FORBIDDEN, "no valid impersonation grant"));
        // The path userId must match the grant's target — a grant is for ONE user.
        if (!target.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "impersonation grant is not for this user");
        }
        User user = users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        return ImpersonatedUserView.from(user);
    }

    /** The recent audit trail (admin console). */
    @GetMapping("/audit")
    public List<AuditEntry> auditLog() {
        return audit.recent(100);
    }

    /**
     * Read-only invariant probe: a mutating request carrying an impersonation
     * token is ALWAYS rejected — an admin may never write as the user (D17).
     */
    @PostMapping("/write-probe")
    public void writeProbe(
        @RequestHeader(name = "X-Impersonation-Token", required = false) String token
    ) {
        if (token != null && !token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "writes are not permitted under read-only impersonation");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no impersonation token");
    }

    // ---- DTOs ----

    public record StartRequest(String targetUserId) {}

    public record StartResponse(String token, String targetUserId, Instant expiresAt) {}

    public record ImpersonatedUserView(
        String userId,
        String email,
        String displayName,
        String status
    ) {
        static ImpersonatedUserView from(User u) {
            return new ImpersonatedUserView(
                u.userId(), u.email(), u.displayName(), u.status().name());
        }
    }
}
