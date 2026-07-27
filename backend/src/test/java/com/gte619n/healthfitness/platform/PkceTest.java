package com.gte619n.healthfitness.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PkceTest {

    // RFC 7636 Appendix B worked example.
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void s256MatchesTheRfcVector() {
        assertThat(Pkce.verify(VERIFIER, CHALLENGE, Pkce.METHOD_S256)).isTrue();
    }

    @Test
    void s256RejectsAWrongVerifier() {
        assertThat(Pkce.verify("not-the-verifier", CHALLENGE, Pkce.METHOD_S256)).isFalse();
    }

    @Test
    void plainComparesVerbatim() {
        assertThat(Pkce.verify("abc123", "abc123", Pkce.METHOD_PLAIN)).isTrue();
        assertThat(Pkce.verify("abc123", "different", Pkce.METHOD_PLAIN)).isFalse();
    }

    @Test
    void unknownMethodNeverVerifies() {
        assertThat(Pkce.verify(VERIFIER, CHALLENGE, "MD5")).isFalse();
        assertThat(Pkce.isSupportedMethod("MD5")).isFalse();
        assertThat(Pkce.isSupportedMethod(Pkce.METHOD_S256)).isTrue();
    }

    @Test
    void nullsNeverVerify() {
        assertThat(Pkce.verify(null, CHALLENGE, Pkce.METHOD_S256)).isFalse();
        assertThat(Pkce.verify(VERIFIER, null, Pkce.METHOD_S256)).isFalse();
        assertThat(Pkce.verify(VERIFIER, CHALLENGE, null)).isFalse();
    }
}
