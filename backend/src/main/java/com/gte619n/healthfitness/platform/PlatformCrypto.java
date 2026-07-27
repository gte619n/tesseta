package com.gte619n.healthfitness.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

// Shared token/secret primitives for the platform OAuth layer: high-entropy
// random tokens, SHA-256 hashing (secrets and codes are only ever persisted as
// a hash), and constant-time comparison. Client secrets, authorization codes,
// and refresh secrets are all 256-bit random values, so a fast hash is
// appropriate — there is no low-entropy password to slow-hash.
public final class PlatformCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PlatformCrypto() {}

    public static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
