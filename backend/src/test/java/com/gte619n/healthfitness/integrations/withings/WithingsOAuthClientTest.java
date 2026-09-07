package com.gte619n.healthfitness.integrations.withings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WithingsOAuthClientTest {

    private HttpServer server;
    private WithingsOAuthClient client;
    private final List<String> requestBodies = new ArrayList<>();
    private String responseBody = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v2/oauth2";
        client = new WithingsOAuthClient(url, "client-id", "client-secret");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @Test
    void exchangeAuthCodeParsesUseridAndTokens() {
        responseBody = """
            {"status":0,"body":{"userid":"98765","access_token":"at-1",
             "refresh_token":"rt-1","expires_in":10800,"scope":"user.metrics","token_type":"Bearer"}}
            """;

        WithingsOAuthClient.TokenGrant grant = client.exchangeAuthCode("the-code", "app://cb");

        assertThat(grant.withingsUserId()).isEqualTo("98765");
        assertThat(grant.accessToken()).isEqualTo("at-1");
        assertThat(grant.refreshToken()).isEqualTo("rt-1");
        assertThat(grant.expiresInSeconds()).isEqualTo(10800);
        // The exact redirect_uri and grant_type must be forwarded.
        assertThat(requestBodies.get(0))
            .contains("grant_type=authorization_code")
            .contains("code=the-code")
            .contains("redirect_uri=app%3A%2F%2Fcb");
    }

    @Test
    void refreshReturnsRotatedRefreshToken() {
        responseBody = """
            {"status":0,"body":{"userid":"98765","access_token":"at-2","refresh_token":"rt-2-rotated",
             "expires_in":10800}}
            """;

        WithingsOAuthClient.TokenGrant grant = client.exchangeRefreshToken("rt-1-old");

        assertThat(grant.refreshToken()).isEqualTo("rt-2-rotated");
        assertThat(requestBodies.get(0))
            .contains("grant_type=refresh_token")
            .contains("refresh_token=rt-1-old");
    }

    @Test
    void invalidGrantIsClassifiedAsPermanentAuthFailure() {
        responseBody = """
            {"status":401,"body":{},"error":"invalid_grant"}
            """;

        assertThatThrownBy(() -> client.exchangeRefreshToken("dead"))
            .isInstanceOf(WithingsAuthException.class);
    }

    @Test
    void rateLimitStaysTransient() {
        responseBody = """
            {"status":601,"body":{},"error":"Too Many Requests"}
            """;

        assertThatThrownBy(() -> client.exchangeRefreshToken("rt"))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(WithingsAuthException.class);
    }
}
