package com.gte619n.healthfitness.integrations.googlehealth;

/**
 * Thrown when Google rejects a refresh-token exchange with a <b>permanent</b>
 * authorization failure ({@code invalid_grant}) — the refresh token is dead:
 * the user revoked access, or (common while the OAuth app is still in Testing
 * mode) Google expired the token after 7 days.
 *
 * <p>Distinct from a generic {@link RuntimeException} so callers can tell a
 * dead connection apart from a transient blip (5xx, network, interrupt). Only
 * this exception should flip a connection into the {@code needsReconnect}
 * state — a transient failure must never mark a healthy connection broken.
 */
public class GoogleHealthAuthException extends RuntimeException {

    public GoogleHealthAuthException(String message) {
        super(message);
    }
}
