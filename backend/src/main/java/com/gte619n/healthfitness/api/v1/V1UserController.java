package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /v1/user — the granted user's profile (ADR-0020). Requires profile:read.
@RestController
@RequestMapping("/v1/user")
@PreAuthorize("hasAuthority('SCOPE_profile:read')")
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "User", description = "The granted user's profile. Requires the `profile:read` scope.")
public class V1UserController {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;

    public V1UserController(CurrentUserProvider currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @Operation(summary = "Get the granted user's profile",
        description = "Returns the profile of the user who authorized this app.")
    @GetMapping
    public UserResponse get() {
        String userId = currentUser.get().userId();
        User user = users.findById(userId)
            .orElseThrow(() -> new NoSuchElementException("user not found"));
        return new UserResponse(user.userId(), user.email(), user.displayName(), user.heightCm());
    }

    @Schema(description = "A Tesseta user's profile.")
    public record UserResponse(
        @Schema(description = "Stable Tesseta user id.") String id,
        @Schema(description = "Account email.") String email,
        @Schema(description = "Display name.") String displayName,
        @Schema(description = "Height in centimetres, if known.") Integer heightCm) {}
}
