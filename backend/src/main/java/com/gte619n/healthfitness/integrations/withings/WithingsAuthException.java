package com.gte619n.healthfitness.integrations.withings;

/**
 * Thrown when a Withings token exchange fails <em>permanently</em> — the
 * refresh token (or authorization code) is no longer usable and the user must
 * reconnect. Classified from the Withings response envelope (an invalid_grant /
 * invalid_token condition), never from a transient error (rate limit, 5xx,
 * transport). Parallels {@code GoogleHealthAuthException}: callers treat it as
 * the signal to mark the connection broken.
 */
public class WithingsAuthException extends RuntimeException {
    public WithingsAuthException(String message) {
        super(message);
    }
}
