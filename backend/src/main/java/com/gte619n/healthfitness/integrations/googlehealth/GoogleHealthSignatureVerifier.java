package com.gte619n.healthfitness.integrations.googlehealth;

import com.google.crypto.tink.JsonKeysetReader;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.PublicKeyVerify;
import com.google.crypto.tink.signature.SignatureConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Verifies the X-HEALTHAPI-SIGNATURE header Google Health stamps on every
// webhook notification. Google signs the raw JSON body with ECDSA P-256 in
// Tink's wire format (5-byte keyId prefix + DER signature) and publishes the
// corresponding public keyset as a Tink JSON keyset, so we verify with Tink's
// PublicKeyVerify rather than hand-rolling JCA + keyId routing.
// See https://developers.google.com/health/webhooks.
//
// Enforcement is OFF by default (app.googlehealth.signature-verify=false) so
// the shared-secret Authorization header remains the sole gate until an
// operator opts in. Rollout: deploy, confirm logs show signatures validating,
// then flip the flag. A verification failure under enforcement returns 401,
// which Google retries for 7 days — i.e. flipping back is a config change, not
// a data-loss event.
@Component
public class GoogleHealthSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleHealthSignatureVerifier.class);
    public static final String SIGNATURE_HEADER = "X-HEALTHAPI-SIGNATURE";

    static {
        try {
            SignatureConfig.register();
        } catch (java.security.GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final boolean enforced;
    private final String keysetUrl;
    private final HttpClient http;
    // Cached verifier built from the published keyset. Refreshed on a verify
    // miss so key rotations heal without a redeploy.
    private final AtomicReference<PublicKeyVerify> verifier = new AtomicReference<>();

    @Autowired
    public GoogleHealthSignatureVerifier(
        @Value("${app.googlehealth.signature-verify:false}") boolean enforced,
        @Value("${app.googlehealth.signature-keyset-url:"
            + "https://www.gstatic.com/googlehealthapi/webhooks/webhooks_public_keyset.json}")
        String keysetUrl
    ) {
        this(enforced, keysetUrl, HttpClient.newBuilder().build());
    }

    // Test seam: inject the HTTP client (or, via withKeyset, a fixed keyset).
    GoogleHealthSignatureVerifier(boolean enforced, String keysetUrl, HttpClient http) {
        this.enforced = enforced;
        this.keysetUrl = keysetUrl;
        this.http = http;
    }

    // Builds a verifier pinned to an explicit keyset JSON. Used by tests to
    // verify the crypto path without reaching gstatic.
    static GoogleHealthSignatureVerifier withKeyset(boolean enforced, String keysetJson) {
        GoogleHealthSignatureVerifier v =
            new GoogleHealthSignatureVerifier(enforced, "", HttpClient.newBuilder().build());
        v.verifier.set(buildVerifier(keysetJson));
        return v;
    }

    public boolean enforced() {
        return enforced;
    }

    // Returns true iff the header carries a valid signature over the raw body.
    // Never throws — a malformed header, fetch failure, or bad signature all
    // resolve to false, and the caller decides whether that blocks the request.
    public boolean verify(String signatureHeader, byte[] rawBody) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureHeader.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Webhook signature header is not valid base64");
            return false;
        }
        if (tryVerify(signature, rawBody)) {
            return true;
        }
        // A miss may mean the signing key rotated; refresh once and retry.
        verifier.set(null);
        return tryVerify(signature, rawBody);
    }

    private boolean tryVerify(byte[] signature, byte[] rawBody) {
        PublicKeyVerify v = currentVerifier();
        if (v == null) {
            return false;
        }
        try {
            v.verify(signature, rawBody);
            return true;
        } catch (java.security.GeneralSecurityException e) {
            return false;
        }
    }

    private PublicKeyVerify currentVerifier() {
        PublicKeyVerify cached = verifier.get();
        if (cached != null) {
            return cached;
        }
        try {
            PublicKeyVerify built = buildVerifier(fetchKeyset());
            verifier.set(built);
            return built;
        } catch (RuntimeException e) {
            log.error("Failed to load Google Health webhook keyset from {}: {}",
                keysetUrl, e.getMessage());
            return null;
        }
    }

    private String fetchKeyset() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(keysetUrl)).GET().build();
            HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException("keyset fetch status " + response.statusCode());
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("keyset fetch failed", e);
        }
    }

    private static PublicKeyVerify buildVerifier(String keysetJson) {
        try {
            KeysetHandle handle =
                KeysetHandle.readNoSecret(JsonKeysetReader.withString(keysetJson));
            return handle.getPrimitive(PublicKeyVerify.class);
        } catch (java.io.IOException | java.security.GeneralSecurityException e) {
            throw new RuntimeException("invalid keyset", e);
        }
    }
}
