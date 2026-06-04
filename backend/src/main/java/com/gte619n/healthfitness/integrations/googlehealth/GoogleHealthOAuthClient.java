package com.gte619n.healthfitness.integrations.googlehealth;

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

// Tiny OAuth2 token-exchange client: refresh_token grant against Google.
// We don't pull in google-auth-library here because (a) we already have
// java.net.http for the rest of the Google Health calls, (b) the four-
// field request below is the entire contract.
@Component
public class GoogleHealthOAuthClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;

    public GoogleHealthOAuthClient(
        @Value("${app.googlehealth.oauth-token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
        @Value("${app.googlehealth.web-oauth-client-id:}") String clientId,
        @Value("${app.googlehealth.web-oauth-client-secret:}") String clientSecret
    ) {
        this.http = HttpClient.newBuilder().build();
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public AccessTokenGrant exchangeRefreshToken(String refreshToken) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                "Google Health OAuth client credentials are not configured");
        }
        String body = formEncode(
            "grant_type", "refresh_token",
            "refresh_token", refreshToken,
            "client_id", clientId,
            "client_secret", clientSecret
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "Token exchange failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            return new AccessTokenGrant(
                json.path("access_token").asText(),
                json.path("expires_in").asLong()
            );
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Token exchange interrupted/failed", e);
        }
    }

    public AuthCodeGrant exchangeAuthCode(String serverAuthCode) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException(
                "Google Health OAuth client credentials are not configured");
        }
        // GIS Android requestOfflineAccess(webClientId, true) hands us a
        // server auth code. Redeeming it uses the web client id/secret with
        // an EMPTY redirect_uri (not "postmessage") and no PKCE.
        String body = formEncode(
            "grant_type", "authorization_code",
            "code", serverAuthCode,
            "client_id", clientId,
            "client_secret", clientSecret,
            "redirect_uri", ""
        );
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new RuntimeException(
                    "Auth code exchange failed (" + response.statusCode() + "): " + response.body());
            }
            JsonNode json = mapper.readTree(response.body());
            String refreshToken = json.path("refresh_token").asText();
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new IllegalStateException("Auth code exchange returned no refresh token");
            }
            return new AuthCodeGrant(
                refreshToken,
                json.path("access_token").asText(),
                json.path("expires_in").asLong()
            );
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Auth code exchange interrupted/failed", e);
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

    public record AccessTokenGrant(String accessToken, long expiresInSeconds) {}

    public record AuthCodeGrant(String refreshToken, String accessToken, long expiresInSeconds) {}
}
