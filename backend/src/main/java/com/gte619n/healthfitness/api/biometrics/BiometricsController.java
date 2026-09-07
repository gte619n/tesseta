package com.gte619n.healthfitness.api.biometrics;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Biometrics settings: list every metric with its visibility + latest reading +
// 60-day cadence, and persist which metrics the user has hidden from the
// dashboard. The hidden set is stored on the user (synced per-account) and also
// surfaced on GET /api/me so the dashboard can filter cards without this call.
@RestController
@RequestMapping("/api/me/biometrics")
public class BiometricsController {

    private final CurrentUserProvider currentUser;
    private final UserRepository users;
    private final BiometricsService service;

    public BiometricsController(
        CurrentUserProvider currentUser,
        UserRepository users,
        BiometricsService service
    ) {
        this.currentUser = currentUser;
        this.users = users;
        this.service = service;
    }

    @GetMapping
    public List<BiometricSummary> list() {
        String userId = currentUser.get().userId();
        User user = users.findById(userId).orElse(null);
        List<String> hidden = user == null ? List.of() : user.hiddenBiometrics();
        return service.summaries(userId, hidden);
    }

    // Replace the hidden set (full set, not a delta). Unknown keys are dropped so
    // a client can't persist junk; the response echoes the fresh summaries with
    // updated `visible` flags.
    @PutMapping("/visibility")
    public List<BiometricSummary> setVisibility(@RequestBody VisibilityRequest body) {
        String userId = currentUser.get().userId();
        List<String> hidden = (body.hidden() == null ? List.<String>of() : body.hidden()).stream()
            .filter(k -> Biometric.tryFrom(k).isPresent())
            .distinct()
            .toList();
        users.updateHiddenBiometrics(userId, hidden);
        return service.summaries(userId, hidden);
    }

    public record VisibilityRequest(List<String> hidden) {}
}
