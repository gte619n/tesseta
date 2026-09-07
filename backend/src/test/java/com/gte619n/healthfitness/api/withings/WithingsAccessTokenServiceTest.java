package com.gte619n.healthfitness.api.withings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import com.gte619n.healthfitness.core.user.WithingsConnection;
import com.gte619n.healthfitness.integrations.googlehealth.KmsTokenCipher;
import com.gte619n.healthfitness.integrations.withings.WithingsAuthException;
import com.gte619n.healthfitness.integrations.withings.WithingsOAuthClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;

class WithingsAccessTokenServiceTest {

    private static final byte[] OLD_CT = {1, 2, 3};
    private static final byte[] DEK = {4, 5, 6};
    private static final byte[] NEW_CT = {9, 9, 9};

    private UserRepository users;
    private KmsTokenCipher cipher;
    private WithingsOAuthClient oauth;
    private ApplicationEventPublisher events;
    private WithingsAccessTokenService service;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        cipher = Mockito.mock(KmsTokenCipher.class);
        oauth = Mockito.mock(WithingsOAuthClient.class);
        events = Mockito.mock(ApplicationEventPublisher.class);
        service = new WithingsAccessTokenService(users, cipher, oauth, events);
        when(cipher.decrypt(any())).thenReturn("old-refresh");
    }

    private static User userWith(WithingsConnection w) {
        return new User("u-1", "u@x", "U", null, null, Instant.EPOCH, Instant.EPOCH, null, null, w);
    }

    private static WithingsConnection healthy() {
        return new WithingsConnection("wid-1", OLD_CT, DEK, Instant.EPOCH, null, null);
    }

    @Test
    void refreshPersistsRotatedTokenAndReturnsAccessToken() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(healthy())));
        when(oauth.exchangeRefreshToken("old-refresh"))
            .thenReturn(new WithingsOAuthClient.TokenGrant("wid-1", "access-new", "refresh-new", 10800));
        when(cipher.encrypt("refresh-new")).thenReturn(new KmsTokenCipher.EncryptedToken(NEW_CT, DEK));

        String token = service.accessTokenFor("u-1");

        assertThat(token).isEqualTo("access-new");
        // The rotated refresh token is re-encrypted and persisted.
        verify(users).recordWithingsConnection(eq("u-1"), argThat(c ->
            Arrays.equals(c.refreshTokenCiphertext(), NEW_CT)
                && c.withingsUserId().equals("wid-1")
                && c.brokenAt() == null));
    }

    @Test
    void permanentAuthFailureMarksBrokenAndPublishesOnce() {
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(healthy())));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenThrow(new WithingsAuthException("invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor("u-1"))
            .isInstanceOf(WithingsAuthException.class);

        verify(users).markWithingsBroken(eq("u-1"), anyString());
        verify(events).publishEvent(any(WithingsConnectionBrokenEvent.class));
    }

    @Test
    void alreadyBrokenDoesNotRepublish() {
        WithingsConnection broken =
            new WithingsConnection("wid-1", OLD_CT, DEK, Instant.EPOCH, Instant.EPOCH, "invalid_grant");
        when(users.findById("u-1")).thenReturn(Optional.of(userWith(broken)));
        when(oauth.exchangeRefreshToken(anyString()))
            .thenThrow(new WithingsAuthException("invalid_grant"));

        assertThatThrownBy(() -> service.accessTokenFor("u-1"))
            .isInstanceOf(WithingsAuthException.class);

        verify(users, never()).markWithingsBroken(anyString(), anyString());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void seededTokenAvoidsRefresh() {
        service.seed("u-1", "seeded-access", 10800);

        assertThat(service.accessTokenFor("u-1")).isEqualTo("seeded-access");
        verify(oauth, never()).exchangeRefreshToken(anyString());
    }
}
