package com.gte619n.healthfitness.api.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthAuthException;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GoogleHealthHealthCheckServiceTest {

    private InMemoryUserRepository users;
    private AccessTokenService tokens;
    private GoogleHealthHealthCheckService service;

    @BeforeEach
    void setUp() {
        users = new InMemoryUserRepository();
        tokens = Mockito.mock(AccessTokenService.class);
        service = new GoogleHealthHealthCheckService(users, tokens);
    }

    private void seed(String id, boolean connected) {
        GoogleHealthConnection gh = connected
            ? new GoogleHealthConnection("h-" + id, new byte[]{1}, new byte[]{2}, Instant.EPOCH, null, null)
            : null;
        users.save(new User(id, id + "@x.com", id, gh, null, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void probesOnlyConnectedUsers_andCountsHealthyVsBroken() {
        seed("healthy", true);
        seed("dead", true);
        seed("disconnected", false);

        when(tokens.accessTokenFor("healthy")).thenReturn("at");
        when(tokens.accessTokenFor("dead"))
            .thenThrow(new GoogleHealthAuthException("invalid_grant"));

        GoogleHealthHealthCheckService.Summary summary = service.checkAll();

        assertThat(summary.connected()).isEqualTo(2);
        assertThat(summary.healthy()).isEqualTo(1);
        assertThat(summary.broken()).isEqualTo(1);
        // The disconnected user is never probed.
        verify(tokens, never()).accessTokenFor("disconnected");
        verify(tokens).accessTokenFor(eq("dead"));
    }

    @Test
    void oneFailureDoesNotAbortTheSweep() {
        seed("a", true);
        seed("b", true);
        when(tokens.accessTokenFor(anyString()))
            .thenThrow(new RuntimeException("boom"));

        GoogleHealthHealthCheckService.Summary summary = service.checkAll();

        assertThat(summary.connected()).isEqualTo(2);
        assertThat(summary.broken()).isEqualTo(2);
    }
}
