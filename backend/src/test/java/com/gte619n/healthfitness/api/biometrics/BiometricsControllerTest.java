package com.gte619n.healthfitness.api.biometrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.auth.CurrentUser;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BiometricsControllerTest {

    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final UserRepository users = mock(UserRepository.class);
    private final BiometricsService service = mock(BiometricsService.class);
    private final BiometricsController controller =
        new BiometricsController(currentUser, users, service);

    @BeforeEach
    void setUp() {
        when(currentUser.get()).thenReturn(new CurrentUser("u", "e@x", "N", null));
        when(service.summaries(eq("u"), any())).thenReturn(List.of());
    }

    @Test
    void setVisibilityDropsUnknownKeysAndPersistsTheRest() {
        controller.setVisibility(new BiometricsController.VisibilityRequest(
            List.of("SLEEP", "BOGUS", "HRV", "SLEEP"))); // unknown + duplicate

        verify(users).updateHiddenBiometrics("u", List.of("SLEEP", "HRV"));
    }

    @Test
    void listComputesAgainstTheUsersHiddenSet() {
        User u = new User("u", "e@x", "N", null, null, Instant.EPOCH, Instant.EPOCH,
            null, null, null, List.of("STEPS"));
        when(users.findById("u")).thenReturn(Optional.of(u));

        controller.list();

        verify(service).summaries("u", List.of("STEPS"));
    }
}
