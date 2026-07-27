package com.gte619n.healthfitness.platform;

import org.springframework.http.HttpStatus;

// An OAuth 2.0 protocol error (RFC 6749 §5.2 / §4.1.2.1). Carries the standard
// `error` code and a human `error_description`; the controller renders it as the
// spec's JSON error body with the mapped status. invalid_client is a 401 (the
// client failed to authenticate); every other protocol error is a 400.
public class OAuthException extends RuntimeException {

    private final String error;

    public OAuthException(String error, String description) {
        super(description);
        this.error = error;
    }

    public String error() {
        return error;
    }

    public HttpStatus status() {
        return "invalid_client".equals(error) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
    }

    // --- Common constructors for the standard error codes. ---

    public static OAuthException invalidRequest(String description) {
        return new OAuthException("invalid_request", description);
    }

    public static OAuthException invalidClient(String description) {
        return new OAuthException("invalid_client", description);
    }

    public static OAuthException invalidGrant(String description) {
        return new OAuthException("invalid_grant", description);
    }

    public static OAuthException invalidScope(String description) {
        return new OAuthException("invalid_scope", description);
    }

    public static OAuthException unsupportedGrantType(String description) {
        return new OAuthException("unsupported_grant_type", description);
    }
}
