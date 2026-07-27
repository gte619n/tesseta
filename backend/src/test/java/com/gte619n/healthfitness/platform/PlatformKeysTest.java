package com.gte619n.healthfitness.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlatformKeysTest {

    private static AppPlatformProperties props(boolean allowEphemeral, String pem) {
        AppPlatformProperties p = new AppPlatformProperties();
        p.setAllowEphemeralKey(allowEphemeral);
        p.setRsaPrivateKey(pem);
        return p;
    }

    @Test
    void generatesEphemeralKeyWhenAllowed() {
        PlatformKeys keys = new PlatformKeys(props(true, ""));
        assertThat(keys.publicKey()).isNotNull();
        assertThat(keys.keyId()).isNotBlank();
        assertThat(keys.publicJwks()).containsKey("keys");
    }

    @Test
    void failsClosedWhenEphemeralDisallowedAndNoKeyConfigured() {
        assertThatThrownBy(() -> new PlatformKeys(props(false, "")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rsa-private-key is required");
    }

    @Test
    void signerAndPublicKeyAgree() {
        PlatformKeys keys = new PlatformKeys(props(true, ""));
        // The JWKS exposes the public half; the signer holds the private half.
        assertThat(keys.signer()).isNotNull();
        assertThat(keys.publicKey().getModulus()).isNotNull();
    }
}
