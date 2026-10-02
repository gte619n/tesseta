package com.gte619n.healthfitness.core.user;

import com.gte619n.healthfitness.core.access.EmailAllowlistService;
import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.RefreshTokenStore;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    private final UserRepository users;
    private final EmailAllowlistService allowlist;
    private final AdminBootstrap adminBootstrap;
    private final RefreshTokenStore refreshTokens;

    public UserService(
        UserRepository users,
        EmailAllowlistService allowlist,
        AdminBootstrap adminBootstrap,
        RefreshTokenStore refreshTokens
    ) {
        this.users = users;
        this.allowlist = allowlist;
        this.adminBootstrap = adminBootstrap;
        this.refreshTokens = refreshTokens;
    }

    /**
     * Ensure a {@code users/{sub}} doc exists. New users are gated by the invite
     * allowlist (IMPL-MULTIUSER-01 P1.3): a listed email (or a bootstrap admin)
     * is provisioned ACTIVE; everyone else lands PENDING_APPROVAL with no app
     * access until an admin approves. Bootstrap admins ({@code ADMIN_EMAILS}) are
     * additionally seeded the ADMIN role (P1.2).
     */
    public void provisionIfAbsent(CurrentUser current) {
        if (users.findById(current.userId()).isPresent()) {
            return;
        }
        boolean bootstrapAdmin = adminBootstrap.isBootstrapAdmin(current.email());
        boolean allowed = bootstrapAdmin || allowlist.isAllowed(current.email());
        UserStatus status = allowed ? UserStatus.ACTIVE : UserStatus.PENDING_APPROVAL;
        Set<UserRole> roles = bootstrapAdmin
            ? EnumSet.of(UserRole.USER, UserRole.ADMIN)
            : EnumSet.of(UserRole.USER);

        Instant now = Instant.now();
        users.save(new User(
            current.userId(),
            current.email(),
            current.displayName(),
            null,
            null,
            now,
            now,
            null,
            null,
            null,
            List.of(),
            roles,
            status
        ));
    }

    // ---- admin console mutations (P1.5) ----

    /** Approve a pending user → ACTIVE. No-op if already active. */
    public void approve(String userId) {
        setStatus(userId, UserStatus.ACTIVE);
    }

    /**
     * Change a user's account status. Blocks removing the last active admin from
     * service (last-admin protection) and, for DISABLED, burns the refresh-token
     * family immediately so existing sessions die (decision D15).
     */
    public void setStatus(String userId, UserStatus status) {
        User user = require(userId);
        if (status != UserStatus.ACTIVE && user.isAdmin() && isLastActiveAdmin(userId)) {
            throw new LastAdminException("cannot deactivate the last active admin");
        }
        if (status == UserStatus.ACTIVE && user.deletionScheduledAt() != null) {
            // Reactivating within the grace window cancels the scheduled purge (D13).
            users.updateDeletionSchedule(userId, UserStatus.ACTIVE, null);
            return;
        }
        users.updateStatus(userId, status);
        if (status == UserStatus.DISABLED || status == UserStatus.SUSPENDED) {
            // Suspended/disabled sessions must not be refreshable; burn the family.
            refreshTokens.revokeAllForUser(userId);
        }
    }

    // ---- offboarding (P1.7 / D13) ----

    /** The hard-deletion grace window after scheduling (D13: 30 days). */
    public static final java.time.Duration DELETION_GRACE = java.time.Duration.ofDays(30);

    /**
     * Schedule the account for hard deletion (D13): set {@code status = DISABLED}
     * and {@code deletionScheduledAt = now + 30d}, and burn the refresh-token
     * family so the session dies immediately (D15). Reactivating via
     * {@link #setStatus}(ACTIVE) within the window cancels it. Returns the
     * scheduled purge instant.
     */
    public Instant scheduleDeletion(String userId) {
        User user = require(userId);
        if (user.isAdmin() && isLastActiveAdmin(userId)) {
            throw new LastAdminException("cannot schedule deletion of the last active admin");
        }
        Instant scheduledAt = Instant.now().plus(DELETION_GRACE);
        users.updateDeletionSchedule(userId, UserStatus.DISABLED, scheduledAt);
        refreshTokens.revokeAllForUser(userId);
        return scheduledAt;
    }

    /**
     * Purge every account whose grace window has elapsed as of {@code now}
     * (hard-delete the user doc). Returns the ids purged. Used by the
     * {@code data-purge} Cloud Run Job. Subcollection cascade is a known
     * limitation (see decision log).
     */
    public List<String> purgeDue(Instant now) {
        List<String> purged = new java.util.ArrayList<>();
        for (User u : users.findDeletionDue(now)) {
            users.deleteById(u.userId());
            purged.add(u.userId());
        }
        return purged;
    }

    public void grantRole(String userId, UserRole role) {
        User user = require(userId);
        Set<UserRole> roles = EnumSet.copyOf(user.roles());
        roles.add(role);
        users.updateRoles(userId, roles);
    }

    /** Revoke a role; blocks removing ADMIN from the last active admin. */
    public void revokeRole(String userId, UserRole role) {
        User user = require(userId);
        if (role == UserRole.ADMIN && user.isAdmin() && isLastActiveAdmin(userId)) {
            throw new LastAdminException("cannot remove ADMIN from the last active admin");
        }
        Set<UserRole> roles = EnumSet.copyOf(user.roles());
        roles.remove(role);
        if (roles.isEmpty()) {
            roles.add(UserRole.USER);
        }
        users.updateRoles(userId, roles);
    }

    private User require(String userId) {
        return users.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
    }

    /**
     * True when {@code userId} is the only admin whose account is still ACTIVE.
     * Bootstrap admins ({@code ADMIN_EMAILS}) are a permanent fallback, so when
     * any bootstrap admin exists this is always false — you can never strand the
     * console (the env admin can always get back in).
     */
    private boolean isLastActiveAdmin(String userId) {
        if (!adminBootstrap.emails().isEmpty()) {
            return false;
        }
        long otherActiveAdmins = users.findAdmins().stream()
            .filter(u -> !u.userId().equals(userId))
            .filter(u -> u.status() == UserStatus.ACTIVE)
            .count();
        return otherActiveAdmins == 0;
    }

    /** Thrown when an admin action would strand the system with no usable admin. */
    public static class LastAdminException extends RuntimeException {
        public LastAdminException(String message) {
            super(message);
        }
    }
}
