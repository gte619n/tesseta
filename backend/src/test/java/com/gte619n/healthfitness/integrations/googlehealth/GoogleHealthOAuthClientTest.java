package com.gte619n.healthfitness.integrations.googlehealth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoogleHealthOAuthClientTest {

    private HttpServer server;
    private String tokenUrl;
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final List<Response> responses = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void exchangeAuthCode_sendsAuthorizationCodeGrantWithEmptyRedirectUri() {
        responses.add(new Response(200, """
            { "access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3599,
              "token_type": "Bearer", "scope": "..." }
            """));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");
        GoogleHealthOAuthClient.AuthCodeGrant grant = client.exchangeAuthCode("the-code");

        assertThat(requests).hasSize(1);
        RecordedRequest req = requests.get(0);
        assertThat(req.headers).containsEntry("content-type", "application/x-www-form-urlencoded");
        assertThat(req.form)
            .containsEntry("grant_type", "authorization_code")
            .containsEntry("code", "the-code")
            .containsEntry("client_id", "web-id")
            .containsEntry("client_secret", "web-secret")
            .containsEntry("redirect_uri", ""); // critical: empty string, NOT "postmessage"

        assertThat(grant.refreshToken()).isEqualTo("rt-1");
        assertThat(grant.accessToken()).isEqualTo("at-1");
        assertThat(grant.expiresInSeconds()).isEqualTo(3599);
    }

    @Test
    void exchangeAuthCode_throwsWhenNoRefreshTokenReturned() {
        responses.add(new Response(200, """
            { "access_token": "at-1", "expires_in": 3599 }
            """));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");

        assertThatThrownBy(() -> client.exchangeAuthCode("the-code"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refresh token");
    }

    @Test
    void exchangeAuthCode_throwsWhenCredentialsNotConfigured() {
        GoogleHealthOAuthClient client = new GoogleHealthOAuthClient(tokenUrl, "", "");

        assertThatThrownBy(() -> client.exchangeAuthCode("the-code"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("credentials are not configured");
    }

    @Test
    void exchangeAuthCode_throwsOnNon2xx() {
        responses.add(new Response(400, """
            { "error": "invalid_grant" }
            """));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");

        assertThatThrownBy(() -> client.exchangeAuthCode("the-code"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Auth code exchange failed (400)")
            .hasMessageContaining("invalid_grant");
    }

    @Test
    void exchangeRefreshToken_deadToken_throwsAuthException() {
        // A dead refresh token comes back as 400 invalid_grant — must be
        // classified as GoogleHealthAuthException so callers mark it broken.
        responses.add(new Response(400, """
            { "error": "invalid_grant", "error_description": "Token has been expired or revoked." }
            """));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");

        assertThatThrownBy(() -> client.exchangeRefreshToken("dead-rt"))
            .isInstanceOf(GoogleHealthAuthException.class)
            .hasMessageContaining("invalid_grant");
    }

    @Test
    void exchangeRefreshToken_serverError_throwsGenericRuntime() {
        // A transient 5xx must NOT be classified as an auth failure, so a blip
        // never marks a healthy connection broken.
        responses.add(new Response(503, "upstream unavailable"));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");

        assertThatThrownBy(() -> client.exchangeRefreshToken("rt"))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(GoogleHealthAuthException.class)
            .hasMessageContaining("Token exchange failed (503)");
    }

    @Test
    void exchangeRefreshToken_success_returnsAccessToken() {
        responses.add(new Response(200, """
            { "access_token": "at-2", "expires_in": 3599 }
            """));

        GoogleHealthOAuthClient client =
            new GoogleHealthOAuthClient(tokenUrl, "web-id", "web-secret");
        GoogleHealthOAuthClient.AccessTokenGrant grant = client.exchangeRefreshToken("rt");

        assertThat(grant.accessToken()).isEqualTo("at-2");
        assertThat(grant.expiresInSeconds()).isEqualTo(3599);
        RecordedRequest req = requests.get(0);
        assertThat(req.form)
            .containsEntry("grant_type", "refresh_token")
            .containsEntry("refresh_token", "rt");
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.get(0)));
        byte[] requestBytes = exchange.getRequestBody().readAllBytes();
        Map<String, String> form = parseForm(new String(requestBytes, StandardCharsets.UTF_8));
        requests.add(new RecordedRequest(exchange.getRequestURI().getPath(), headers, form));

        Response response = responses.isEmpty() ? new Response(200, "{}") : responses.remove(0);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = response.body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(response.status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private static Map<String, String> parseForm(String raw) {
        Map<String, String> result = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
                continue;
            }
            // value may be the empty string (e.g. "redirect_uri=")
            result.put(
                URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private record RecordedRequest(String path, Map<String, String> headers, Map<String, String> form) {}

    private record Response(int status, String body) {}
}
