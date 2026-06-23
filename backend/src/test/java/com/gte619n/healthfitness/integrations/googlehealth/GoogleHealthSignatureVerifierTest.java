package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.crypto.tink.JsonKeysetWriter;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeySign;
import com.google.crypto.tink.signature.SignatureConfig;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

// Exercises the ECDSA verification path with a locally generated Tink keyset,
// so the crypto is validated end-to-end without reaching gstatic. The wire
// format (Tink keyId prefix + DER) round-trips because both signer and
// verifier are Tink.
class GoogleHealthSignatureVerifierTest {

    private static KeysetHandle privateHandle;
    private static String publicKeysetJson;

    @BeforeAll
    static void generateKeyset() throws Exception {
        SignatureConfig.register();
        privateHandle = KeysetHandle.generateNew(KeyTemplates.get("ECDSA_P256"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        privateHandle.getPublicKeysetHandle()
            .writeNoSecret(JsonKeysetWriter.withOutputStream(out));
        publicKeysetJson = out.toString(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] payload) throws Exception {
        PublicKeySign signer = privateHandle.getPrimitive(PublicKeySign.class);
        return Base64.getEncoder().encodeToString(signer.sign(payload));
    }

    @Test
    void acceptsAValidSignatureOverTheRawBody() throws Exception {
        byte[] body = "{\"data\":{\"healthUserId\":\"42\"}}".getBytes(StandardCharsets.UTF_8);
        var verifier = GoogleHealthSignatureVerifier.withKeyset(true, publicKeysetJson);

        assertThat(verifier.verify(sign(body), body)).isTrue();
    }

    @Test
    void rejectsASignatureOverDifferentBytes() throws Exception {
        byte[] signedBody = "original".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "tampered".getBytes(StandardCharsets.UTF_8);
        var verifier = GoogleHealthSignatureVerifier.withKeyset(true, publicKeysetJson);

        assertThat(verifier.verify(sign(signedBody), tamperedBody)).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        var verifier = GoogleHealthSignatureVerifier.withKeyset(true, publicKeysetJson);

        assertThat(verifier.verify(null, body)).isFalse();
        assertThat(verifier.verify("   ", body)).isFalse();
        assertThat(verifier.verify("@@@not-base64@@@", body)).isFalse();
    }

    @Test
    void enforcedReflectsConfiguredFlag() {
        assertThat(GoogleHealthSignatureVerifier.withKeyset(true, publicKeysetJson).enforced())
            .isTrue();
        assertThat(GoogleHealthSignatureVerifier.withKeyset(false, publicKeysetJson).enforced())
            .isFalse();
    }
}
