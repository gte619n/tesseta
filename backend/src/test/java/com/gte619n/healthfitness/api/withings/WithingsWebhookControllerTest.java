package com.gte619n.healthfitness.api.withings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.WithingsConnection;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WithingsWebhookControllerTest {

    private static final String SECRET = "s3cr3t";

    private UserRepository users;
    private WithingsSyncService sync;
    private WithingsWebhookController controller;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        sync = Mockito.mock(WithingsSyncService.class);
        controller = new WithingsWebhookController(SECRET, users, sync);
        when(users.findByWithingsUserId("wid-1")).thenReturn(Optional.of(new User(
            "u-1", "u@x", "U", null, null, Instant.EPOCH, Instant.EPOCH, null, null,
            new WithingsConnection("wid-1", new byte[]{1}, new byte[]{2}, Instant.EPOCH, null, null))));
    }

    @Test
    void sleepNotificationRoutesToImportSleepWithInterval() {
        var response = controller.receive(SECRET, "wid-1", 44, 1000L, 2000L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(sync).importSleep("u-1",
            Instant.ofEpochSecond(1000), Instant.ofEpochSecond(2000));
        verify(sync, never()).importBody(anyString(), any(), any());
    }

    @Test
    void weightNotificationRoutesToImportBody() {
        var response = controller.receive(SECRET, "wid-1", 1, 1000L, 2000L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(sync).importBody("u-1",
            Instant.ofEpochSecond(1000), Instant.ofEpochSecond(2000));
    }

    @Test
    void badSecretIsRejectedAndNothingRuns() {
        var response = controller.receive("wrong", "wid-1", 44, 1000L, 2000L);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(sync);
    }

    @Test
    void verificationProbeIsAcknowledged() {
        // Subscribe-time probe: valid secret, no userid/appli.
        var response = controller.receive(SECRET, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(sync);
    }

    @Test
    void unknownWithingsUserIsAcknowledgedWithoutImport() {
        when(users.findByWithingsUserId("ghost")).thenReturn(Optional.empty());

        var response = controller.receive(SECRET, "ghost", 44, 1000L, 2000L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(sync, never()).importSleep(anyString(), any(), any());
    }
}
