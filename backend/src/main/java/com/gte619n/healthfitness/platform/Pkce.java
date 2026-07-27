package com.gte619n.healthfitness.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

// PKCE (RFC 7636) verification. Every platform authorization uses PKCE — there
// is no code-challenge-less path (ADR-0020), so this protects even confidential
// clients against authorization-code interception.
//
// The client sends a `code_challenge` (+ method) at /oauth/authorize and the
// matching `code_verifier` at /oauth/token; we recompute the challenge from the
// verifier and compare in constant time.
public final class Pkce {

    public static final String METHOD_S256 = "S256";
    public static final String METHOD_PLAIN = "plain";

    private Pkce() {}

    public static boolean isSupportedMethod(String method) {
        return METHOD_S256.equals(method) || METHOD_PLAIN.equals(method);
    }

    // True iff `verifier` produces `challenge` under `method`. S256 is the
    // recommended method; `plain` is accepted for completeness but a client
    // should always use S256.
    public static boolean verify(String verifier, String challenge, String method) {
        if (verifier == null || challenge == null || method == null) {
            return false;
        }
        String expected = METHOD_S256.equals(method) ? s256(verifier)
            : METHOD_PLAIN.equals(method) ? verifier
            : null;
        if (expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            challenge.getBytes(StandardCharsets.US_ASCII));
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
