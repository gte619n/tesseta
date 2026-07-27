package com.gte619n.healthfitness.platform;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Holds the RSA signing key for platform access tokens (ADR-0020). RS256 (not
// the HS256 used for first-party session tokens) so third parties can validate
// tokens offline against the published JWKS and we can rotate the key.
//
// Loads a PKCS#8 PEM private key from config when present; otherwise generates
// an ephemeral 2048-bit keypair at startup. Ephemeral keys are fine for local
// dev and CI — a restart just invalidates outstanding *access* tokens (~15 min
// TTL); refresh tokens are opaque and survive — but deployed environments must
// supply app.platform.rsa-private-key from Secret Manager for stability.
@Component
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformKeys {

    private static final Logger log = LoggerFactory.getLogger(PlatformKeys.class);

    private final RSAKey rsaKey; // public + private, carrying the kid
    private final RSASSASigner signer;

    public PlatformKeys(AppPlatformProperties props) {
        boolean noKey = props.getRsaPrivateKey() == null || props.getRsaPrivateKey().isBlank();
        if (noKey && !props.isAllowEphemeralKey()) {
            // Fail-closed (D22): a deployed instance with the platform enabled but
            // no configured key must not silently run an ephemeral key that resets
            // every restart (invalidating all live access tokens). Set
            // app.platform.rsa-private-key (PLATFORM_RSA_KEY) from Secret Manager,
            // or set app.platform.allow-ephemeral-key=true for local/dev.
            throw new IllegalStateException(
                "app.platform.rsa-private-key is required when app.platform.allow-ephemeral-key"
                + " is false — configure PLATFORM_RSA_KEY (or set PLATFORM_ALLOW_EPHEMERAL_KEY=true"
                + " for local/dev)");
        }
        RSAKey base = noKey ? generateEphemeral() : fromPem(props.getRsaPrivateKey());
        String kid = props.getKeyId() != null && !props.getKeyId().isBlank()
            ? props.getKeyId()
            : thumbprint(base);
        this.rsaKey = new RSAKey.Builder(base)
            .keyID(kid)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
        try {
            this.signer = new RSASSASigner(this.rsaKey);
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to build platform RSA signer", e);
        }
    }

    public RSASSASigner signer() {
        return signer;
    }

    public String keyId() {
        return rsaKey.getKeyID();
    }

    public RSAPublicKey publicKey() {
        try {
            return rsaKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to expose platform RSA public key", e);
        }
    }

    // The public JWKS served at /oauth/jwks.json (never includes the private
    // key — toPublicJWK strips it).
    public Map<String, Object> publicJwks() {
        return new JWKSet(rsaKey.toPublicJWK()).toJSONObject();
    }

    private static RSAKey generateEphemeral() {
        log.warn("app.platform.rsa-private-key not set — generating an EPHEMERAL RSA key. "
            + "Platform access tokens will not survive a restart. Set the PEM from "
            + "Secret Manager in deployed environments.");
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                .privateKey((RSAPrivateKey) kp.getPrivate())
                .build();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate platform RSA key", e);
        }
    }

    private static RSAKey fromPem(String pem) {
        try {
            String base64 = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            // Reconstruct the public key from the private key's CRT parameters.
            java.security.spec.RSAPublicKeySpec pubSpec = privateToPublic(priv);
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(pubSpec);
            return new RSAKey.Builder(pub).privateKey(priv).build();
        } catch (Exception e) {
            throw new IllegalStateException(
                "app.platform.rsa-private-key is not a valid PKCS#8 RSA PEM", e);
        }
    }

    private static java.security.spec.RSAPublicKeySpec privateToPublic(RSAPrivateKey priv) {
        // A PKCS#8 RSA private key is really a CRT key; downcast to read the
        // public exponent so we can publish the matching public JWK.
        if (priv instanceof java.security.interfaces.RSAPrivateCrtKey crt) {
            return new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
        }
        // Non-CRT keys don't carry the public exponent; require the standard
        // 65537 so we can still serve a JWKS.
        return new java.security.spec.RSAPublicKeySpec(
            priv.getModulus(), java.math.BigInteger.valueOf(65537));
    }

    private static String thumbprint(RSAKey key) {
        try {
            return key.computeThumbprint().toString();
        } catch (JOSEException e) {
            throw new IllegalStateException("failed to compute platform key thumbprint", e);
        }
    }
}
