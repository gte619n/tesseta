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
        return response(cu, users.findById(cu.userId()).orElse(null));
    }

    // Partial profile update: heightCm plus the M3 Mifflin demographics
    // (biologicalSex, dateOfBirth). An omitted (null) field is left unchanged.
    @PatchMapping
    public WhoAmIResponse update(@RequestBody UpdateProfileRequest body) {
        CurrentUser cu = currentUser.get();
        User existing = users.findById(cu.userId()).orElse(null);

        Integer heightCm = body.heightCm() != null ? body.heightCm()
            : (existing == null ? null : existing.heightCm());
        com.gte619n.healthfitness.core.user.BiologicalSex sex = body.biologicalSex() != null
            ? com.gte619n.healthfitness.core.user.BiologicalSex.valueOf(body.biologicalSex())
            : (existing == null ? null : existing.biologicalSex());
        java.time.LocalDate dob = body.dateOfBirth() != null
            ? java.time.LocalDate.parse(body.dateOfBirth())
            : (existing == null ? null : existing.dateOfBirth());

        if (body.heightCm() != null) {
            users.updateHeightCm(cu.userId(), body.heightCm());
        }
        if (body.biologicalSex() != null || body.dateOfBirth() != null) {
            users.updateDemographics(cu.userId(), sex, dob);
        }
        // Echo the intended post-update state (mirrors the pre-M3 behaviour of
        // returning the request value even when the profile doc doesn't exist yet).
        // A profile PATCH never touches biometric visibility — carry it through.
        return new WhoAmIResponse(cu.userId(), cu.email(), cu.displayName(), cu.photoUrl(),
            heightCm, sex == null ? null : sex.name(), dob == null ? null : dob.toString(),
            existing == null ? java.util.List.of() : existing.hiddenBiometrics());
    }

    private static WhoAmIResponse response(CurrentUser cu, User user) {
        Integer heightCm = user == null ? null : user.heightCm();
        String sex = user == null || user.biologicalSex() == null ? null : user.biologicalSex().name();
        String dob = user == null || user.dateOfBirth() == null ? null : user.dateOfBirth().toString();
        return new WhoAmIResponse(cu.userId(), cu.email(), cu.displayName(), cu.photoUrl(), heightCm, sex, dob,
            user == null ? java.util.List.of() : user.hiddenBiometrics());
    }

    public record UpdateProfileRequest(Integer heightCm, String biologicalSex, String dateOfBirth) {}
}
