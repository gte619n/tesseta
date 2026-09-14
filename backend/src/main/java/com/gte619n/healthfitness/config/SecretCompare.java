package com.gte619n.healthfitness.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Constant-time comparison for shared-secret gates (SEC-009). Using a plain
 * {@code String.equals}/{@code Objects.equals} on a secret leaks a timing
 * oracle: the comparison short-circuits on the first differing byte, so an
 * attacker can, in principle, recover the secret byte-by-byte from response
 * timing. {@link MessageDigest#isEqual} is length-safe and does not
 * short-circuit, so it takes (near-)constant time regardless of where the
 * mismatch is.
 *
 * <p>All three secret-gated controllers — the nutrition Cloud Tasks handler and
 * the Google Health / Withings webhooks — use this so the convention is
 * uniform.
 */
public final class SecretCompare {

    private SecretCompare() {}

    /**
     * True iff {@code a} and {@code b} are non-null and byte-for-byte equal
     * (UTF-8), compared in constant time. A null on either side returns false.
     * {@link MessageDigest#isEqual} is itself length-safe, so a length mismatch
     * simply returns false.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));
    }
}
