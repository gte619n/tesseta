package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.api.security.AdminOnly;
import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.UserService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-MULTIUSER-01 P1.7 (D13) — export-then-delete offboarding.
 *
 * <p>{@code POST .../export} assembles a JSON bundle of the target user's data
 * (profile + the user-scoped subcollections reachable via existing repos) and
 * returns it. {@code POST .../schedule-deletion} sets {@code status = DISABLED}
 * and {@code deletionScheduledAt = now + 30d} (reactivating via the admin user
 * console clears it, {@link UserService#setStatus}). A {@code data-purge} Cloud
 * Run Job ({@code DataPurgeJob}) hard-deletes accounts past the grace window.
 *
 * <p><b>Bundle scope (pragmatic):</b> the bundle includes what is readily
 * enumerable per-user via the existing core repositories — the user profile
 * (connection <em>presence</em> flags only, never tokens) and the user's Goals.
 * Other user-scoped subcollections (nutrition days, workout sessions, biometrics
 * history, etc.) are NOT yet included; they are listed under {@code omitted} so
 * the gap is explicit (see decision log). The bundle is proof-tested for tenant
 * isolation (it never contains another user's data).
 */
@RestController
@RequestMapping("/api/admin/users")
@AdminOnly
public class DataLifecycleController {

    private final UserRepository users;
    private final UserService userService;
    private final GoalRepository goals;

    public DataLifecycleController(
        UserRepository users,
        UserService userService,
        GoalRepository goals
    ) {
        this.users = users;
        this.userService = userService;
        this.goals = goals;
    }

    @PostMapping("/{userId}/export")
    public Map<String, Object> export(@PathVariable String userId) {
        User user = users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("exportedAt", Instant.now().toString());
        bundle.put("userId", userId);

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("userId", user.userId());
        profile.put("email", user.email());
        profile.put("displayName", user.displayName());
        profile.put("heightCm", user.heightCm());
        profile.put("biologicalSex", user.biologicalSex() == null ? null : user.biologicalSex().name());
        profile.put("dateOfBirth", user.dateOfBirth() == null ? null : user.dateOfBirth().toString());
        profile.put("roles", user.roles().stream().map(Enum::name).toList());
        profile.put("status", user.status().name());
        profile.put("createdAt", user.createdAt());
        profile.put("updatedAt", user.updatedAt());
        // Connection PRESENCE only — never the encrypted token material (D14/tenant).
        profile.put("googleHealthConnected", user.googleHealth() != null);
        profile.put("withingsConnected", user.withings() != null);
        bundle.put("profile", profile);

        List<Goal> userGoals = goals.findByUser(userId, null);
        bundle.put("goals", userGoals);

        // Explicit record of user-scoped data NOT (yet) in the bundle.
        bundle.put("omitted", List.of(
            "nutritionDays", "workoutSessions", "workoutPrograms", "adHocWorkouts",
            "biometricsHistory", "aiUsageEvents", "connectionTokens(by design)"));

        return bundle;
    }

    @PostMapping("/{userId}/schedule-deletion")
    public ScheduleDeletionResponse scheduleDeletion(@PathVariable String userId) {
        try {
            Instant scheduledAt = userService.scheduleDeletion(userId);
            return new ScheduleDeletionResponse(userId, "DISABLED", scheduledAt);
        } catch (UserService.LastAdminException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    public record ScheduleDeletionResponse(String userId, String status, Instant deletionScheduledAt) {}
}
