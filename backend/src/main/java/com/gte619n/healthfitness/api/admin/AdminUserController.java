package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.access.EmailAllowlistEntry;
import com.gte619n.healthfitness.core.access.EmailAllowlistService;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.UserRole;
import com.gte619n.healthfitness.core.user.UserService;
import com.gte619n.healthfitness.core.user.UserStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin console for managing logins (IMPL-MULTIUSER-01 P1.5): list/search users,
 * inspect a user, change role/status, approve pending signups, and manage the
 * invite allowlist. All endpoints are {@link AdminOnly}.
 */
@RestController
@RequestMapping("/api/admin/users")
@AdminOnly
public class AdminUserController {

    private static final int MAX_LIMIT = 200;

    private final UserRepository users;
    private final UserService userService;
    private final EmailAllowlistService allowlist;
    private final CurrentUserProvider currentUser;

    public AdminUserController(
        UserRepository users,
        UserService userService,
        EmailAllowlistService allowlist,
        CurrentUserProvider currentUser
    ) {
        this.users = users;
        this.userService = userService;
        this.allowlist = allowlist;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<AdminUserSummary> list(
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String status,
        @RequestParam(required = false, defaultValue = "50") int limit
    ) {
        UserStatus statusFilter = parseStatus(status);
        int capped = Math.min(Math.max(1, limit), MAX_LIMIT);
        return users.search(query, statusFilter, capped).stream()
            .map(AdminUserSummary::from)
            .toList();
    }

    @GetMapping("/pending")
    public List<AdminUserSummary> pending() {
        return users.search(null, UserStatus.PENDING_APPROVAL, MAX_LIMIT).stream()
            .map(AdminUserSummary::from)
            .toList();
    }

    @GetMapping("/{userId}")
    public AdminUserDetail detail(@PathVariable String userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        return AdminUserDetail.from(user);
    }

    @PostMapping("/{userId}/status")
    public AdminUserDetail setStatus(@PathVariable String userId, @RequestBody StatusRequest body) {
        UserStatus status = parseStatus(body == null ? null : body.status());
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status required");
        }
        guardSelfLockout(userId, status);
        try {
            userService.setStatus(userId, status);
        } catch (UserService.LastAdminException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return detail(userId);
    }

    @PostMapping("/{userId}/approve")
    public AdminUserDetail approve(@PathVariable String userId) {
        try {
            userService.approve(userId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return detail(userId);
    }

    @PostMapping("/{userId}/role")
    public AdminUserDetail setRole(@PathVariable String userId, @RequestBody RoleRequest body) {
        if (body == null || body.role() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role required");
        }
        UserRole role;
        try {
            role = UserRole.valueOf(body.role());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown role: " + body.role());
        }
        boolean grant = body.grant() == null || body.grant();
        try {
            if (grant) {
                userService.grantRole(userId, role);
            } else {
                userService.revokeRole(userId, role);
            }
        } catch (UserService.LastAdminException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return detail(userId);
    }

    // ---- invite allowlist (P1.3) ----

    @GetMapping("/allowlist")
    public List<AllowlistEntryResponse> listAllowlist() {
        return allowlist.list().stream().map(AllowlistEntryResponse::from).toList();
    }

    @PostMapping("/allowlist")
    public List<AllowlistEntryResponse> addAllowlist(@RequestBody AllowlistRequest body) {
        if (body == null || body.email() == null || body.email().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "email required");
        }
        allowlist.allow(body.email(), currentUser.get().userId());
        return listAllowlist();
    }

    @DeleteMapping("/allowlist/{email}")
    public List<AllowlistEntryResponse> removeAllowlist(@PathVariable String email) {
        allowlist.revoke(email);
        return listAllowlist();
    }

    private UserStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UserStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status: " + raw);
        }
    }

    /** Refuse an admin suspending/disabling their own account (foot-gun guard). */
    private void guardSelfLockout(String userId, UserStatus status) {
        if (!status.isActive() && userId.equals(currentUser.get().userId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "cannot change your own account to " + status);
        }
    }

    // ---- DTOs ----

    public record StatusRequest(String status) {}

    public record RoleRequest(String role, Boolean grant) {}

    public record AllowlistRequest(String email) {}

    public record AdminUserSummary(
        String userId,
        String email,
        String displayName,
        List<String> roles,
        String status,
        Instant createdAt
    ) {
        static AdminUserSummary from(User u) {
            return new AdminUserSummary(
                u.userId(), u.email(), u.displayName(),
                u.roles().stream().map(Enum::name).toList(),
                u.status().name(), u.createdAt());
        }
    }

    public record AdminUserDetail(
        String userId,
        String email,
        String displayName,
        List<String> roles,
        String status,
        Instant createdAt,
        Instant updatedAt,
        boolean googleHealthConnected,
        boolean withingsConnected
    ) {
        static AdminUserDetail from(User u) {
            return new AdminUserDetail(
                u.userId(), u.email(), u.displayName(),
                u.roles().stream().map(Enum::name).toList(),
                u.status().name(), u.createdAt(), u.updatedAt(),
                u.googleHealth() != null, u.withings() != null);
        }
    }

    public record AllowlistEntryResponse(String email, String addedBy, Instant addedAt) {
        static AllowlistEntryResponse from(EmailAllowlistEntry e) {
            return new AllowlistEntryResponse(e.emailLower(), e.addedBy(), e.addedAt());
        }
    }
}
