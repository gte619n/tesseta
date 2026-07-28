package com.gte619n.healthfitness.api.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.user.GoogleHealthConnection;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthAuthException;
import com.gte619n.healthfitness.integrations.googlehealth.GoogleHealthOAuthClient;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class AccessTokenServiceTest {

    private UserRepository users;
    private KmsTokenCipher cipher;
    private GoogleHealthOAuthClient oauth;
    private ApplicationEventPublisher events;
    private AccessTokenService service;

    private static final byte[] RT_CT = {1, 2, 3};
    private static final byte[] DEK_CT = {4, 5, 6};

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        cipher = Mockito.mock(KmsTokenCipher.class);
        oauth = Mockito.mock(GoogleHealthOAuthClient.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        service = new AccessTokenService(users, cipher, oauth, events);
        when(cipher.decrypt(any())).thenReturn("refresh-token");
    }

    private static User userWith(GoogleHealthConnection gh) {
        return new User("u-1", "u@example.com", "U", gh, null, Instant.EPOCH, Instant.EPOCH);
    }

    private static GoogleHealthConnection healthy() {
        return new GoogleHealthConnection("h-1", RT_CT, DEK_CT, Instant.EPOCH, null, null);
    }

    private static GoogleHealthConnection broken() {
        return new GoogleHealthConnection(
            "h-1", RT_CT, DEK_CT, Instant.EPOCH, Instant.EPOCH, "invalid_grant");
    }

    @Test
    void deadToken_marksBrokenAndPublishesOnce() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(healthy())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenThrow(new GoogleHealthAuthException("Token exchange failed (400): invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor("u-1"))
            .isInstanceOf(GoogleHealthAuthException.class);

        verify(users).markGoogleHealthBroken(eq("u-1"), anyString());
        verify(events).publishEvent(any(GoogleHealthConnectionBrokenEvent.class));
    }

    @Test
    void alreadyBroken_doesNotReMarkOrRePublish() {
        // Connection is already flagged broken — a further failure must not
        // re-mark or re-notify (the push fires once per breakage).
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(broken())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenThrow(new GoogleHealthAuthException("still invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor("u-1"))
            .isInstanceOf(GoogleHealthAuthException.class);

        verify(users, never()).markGoogleHealthBroken(anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void transientError_doesNotMarkBroken() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(healthy())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenThrow(new RuntimeException("Token exchange failed (503)"));

        assertThatThrownBy(() -> service.accessTokenFor("u-1"))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(GoogleHealthAuthException.class);

        verify(users, never()).markGoogleHealthBroken(anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void success_afterBroken_clearsFlag() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(broken())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenReturn(new GoogleHealthOAuthClient.AccessTokenGrant("at-1", 3599));

        String token = service.accessTokenFor("u-1");

        assertThat(token).isEqualTo("at-1");
        // Rewrites the connection with brokenAt cleared.
        Mockito.verify(users).recordGoogleHealthConnection(eq("u-1"),
            Mockito.argThat(gh -> !gh.needsReconnect()));
    }

    @Test
    void success_whenHealthy_doesNotRewriteConnection() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(healthy())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenReturn(new GoogleHealthOAuthClient.AccessTokenGrant("at-1", 3599));

        service.accessTokenFor("u-1");

        verify(users, never()).recordGoogleHealthConnection(anyString(), any());
    }
}
