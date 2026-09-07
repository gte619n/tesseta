package com.gte619n.healthfitness.integrations.withings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// OAuth2 token client for Withings. Withings deviates from plain OAuth2 in
// three ways this class hides from callers:
//
//   1. All token calls POST to a single endpoint (/v2/oauth2) with an
//      `action=requesttoken` form field alongside the usual grant_type.
//   2. Responses are wrapped in a { "status": <int>, "body": {...} } envelope.
//      HTTP is 200 even for logical errors; success is status == 0 and the
//      tokens live under `body`.
//   3. The refresh token ROTATES: every requesttoken call (auth-code OR
//      refresh) returns a NEW refresh token that supersedes the one used. The
//      caller MUST persist body.refresh_token after every exchange.
//
// The exchange also yields the numeric Withings `userid`, so — unlike Google
// Health — there's no separate "discover the health user id" data call.
@Component
public class WithingsOAuthClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    public WithingsOAuthClient(
        @Value("${app.withings.oauth-token-url:https://wbsapi.withings.net/v2/oauth2}") String tokenUrl,
        @Value("${app.withings.client-id:}") String clientId,
        @Value("${app.withings.client-secret:}") String clientSecret
    ) {
        this.http = HttpClient.newBuilder().build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Redeem an authorization code obtained from the user's browser/app OAuth
     * redirect. {@code redirectUri} must exactly match the one the client used
     * to start the flow (Withings enforces this), so the caller passes it
     * through from the connect request.
     */
    public TokenGrant exchangeAuthCode(String code, String redirectUri) {
        requireCredentials();
        return request(formEncode(
            "action", "requesttoken",
            "grant_type", "authorization_code",
            "client_id", clientId,
            "client_secret", clientSecret,
            "code", code,
            "redirect_uri", redirectUri
        ));
    }

    /** Exchange a (rotating) refresh token for a fresh access + refresh token pair. */
    public TokenGrant exchangeRefreshToken(String refreshToken) {
        requireCredentials();
        return request(formEncode(
            "action", "requesttoken",
            "grant_type", "refresh_token",
            "client_id", clientId,
            "client_secret", clientSecret,
            "refresh_token", refreshToken
        ));
    }

    private TokenGrant request(String body) {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            int status = json.path("status").asInt(-1);
            if (response.statusCode() / 100 != 2 || status != 0) {
                String detail = "Withings token exchange failed (http="
                    + response.statusCode() + " status=" + status + "): " + response.body();
                // A dead refresh token / consumed-or-expired auth code comes back
                // as an invalid_grant / invalid_token condition. Classify it as a
                // permanent auth failure so the caller marks the connection
                // broken; rate limits (601), 5xx and transport stay transient.
                if (isInvalidGrant(response.statusCode(), status, response.body())) {
                    throw new WithingsAuthException(detail);
                }
                throw new RuntimeException(detail);
            }
            JsonNode b = json.path("body");
            String refreshToken = b.path("refresh_token").asText("");
            if (refreshToken.isBlank()) {
                throw new IllegalStateException("Withings token exchange returned no refresh token");
            }
            return new TokenGrant(
                b.path("userid").asText(),
                b.path("access_token").asText(),
                refreshToken,
                b.path("expires_in").asLong()
            );
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Withings token exchange interrupted/failed", e);
        }
    }

    // Permanent-auth classification. Withings signals a dead refresh token or a
    // spent authorization code with an invalid_grant/invalid_token error (the
    // envelope status is in the 401 family or the body names the OAuth error).
    // Rate limiting (601) and service errors are explicitly NOT permanent.
    private static boolean isInvalidGrant(int httpStatus, int envelopeStatus, String body) {
        if (envelopeStatus == 601) return false; // too many requests — transient
        if (body != null
            && (body.contains("invalid_grant") || body.contains("invalid_token"))) {
            return true;
        }
        // Withings status 401 = unauthorized (token no longer valid).
        return envelopeStatus == 401 || httpStatus == 401;
    }

    private void requireCredentials() {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Withings OAuth client credentials are not configured");
        }
    }

    private static String formEncode(String... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("even args required");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Result of a token exchange. {@code withingsUserId} is the numeric account
     * id (returned on every exchange); {@code refreshToken} is the freshly
     * rotated token the caller must persist.
     */
    public record TokenGrant(
        String withingsUserId,
        String accessToken,
        String refreshToken,
        long expiresInSeconds
    ) {}
}
