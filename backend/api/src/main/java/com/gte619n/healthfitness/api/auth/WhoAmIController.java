package com.gte619n.healthfitness.api.auth;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
public class WhoAmIController {
    private final CurrentUserProvider currentUser;
    private final UserRepository users;

    public WhoAmIController(CurrentUserProvider currentUser, UserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @GetMapping
    public WhoAmIResponse whoAmI() {
        CurrentUser cu = currentUser.get();
        Integer heightCm = users.findById(cu.userId())
            .map(User::heightCm)
            .orElse(null);
        return new WhoAmIResponse(cu.userId(), cu.email(), cu.displayName(), cu.photoUrl(), heightCm);
    }

    // Partial profile update. Only `heightCm` is supported today; the
    // PATCH shape keeps the door open for additional editable profile
    // fields (date of birth, sex, units preference) without another
    // endpoint.
    @PatchMapping
    public WhoAmIResponse update(@RequestBody UpdateProfileRequest body) {
        CurrentUser cu = currentUser.get();
        users.updateHeightCm(cu.userId(), body.heightCm());
        return new WhoAmIResponse(cu.userId(), cu.email(), cu.displayName(), cu.photoUrl(), body.heightCm());
    }

    public record UpdateProfileRequest(Integer heightCm) {}
}
