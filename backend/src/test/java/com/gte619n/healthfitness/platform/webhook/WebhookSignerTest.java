package com.gte619n.healthfitness.platform.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    @Test
    void signatureIsDeterministicAndPrefixed() {
        String sig = WebhookSigner.signatureHeader("{\"a\":1}", "secret");
        assertThat(sig).startsWith("sha256=");
        assertThat(WebhookSigner.signatureHeader("{\"a\":1}", "secret")).isEqualTo(sig);
    }

    @Test
    void differentBodyOrSecretChangesSignature() {
        String base = WebhookSigner.hmacHex("body", "secret");
        assertThat(WebhookSigner.hmacHex("body2", "secret")).isNotEqualTo(base);
        assertThat(WebhookSigner.hmacHex("body", "secret2")).isNotEqualTo(base);
    }

    @Test
    void matchesAKnownHmacVector() {
        // RFC 4231-style: verifiable independently.
        // HMAC-SHA256(key="key", msg="The quick brown fox jumps over the lazy dog")
        String expected = "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8";
        assertThat(WebhookSigner.hmacHex("The quick brown fox jumps over the lazy dog", "key"))
            .isEqualTo(expected);
    }
}
