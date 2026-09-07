package com.gte619n.healthfitness.integrations.withings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Registers this backend as a Withings notification subscriber. Withings pushes
// a lightweight form POST to the callback URL when new data of a subscribed
// category (`appli`) lands; the handler then re-fetches via the data API.
//
// Notify categories (appli) we subscribe to:
//   44 = sleep summary   (the Sleep Analyzer pad)
//    1 = weight          (Withings scales)
//
// Subscription is per-user (uses that user's access token) and per-appli, so we
// issue one subscribe call per category. On subscribe, Withings sends a test
// notification to the callback URL and expects HTTP 200 — see
// WithingsWebhookController's probe handling.
@Component
public class WithingsSubscriberClient {

    /** The notify categories this integration ingests (sleep + weight). */
    public static final List<Integer> SUBSCRIBED_APPLIS = List.of(44, 1);

    private static final Logger log = LoggerFactory.getLogger(WithingsSubscriberClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBaseUrl;

    public WithingsSubscriberClient(
        @Value("${app.withings.api-base-url:https://wbsapi.withings.net}") String apiBaseUrl
    ) {
        this.http = HttpClient.newBuilder().build();
        this.apiBaseUrl = apiBaseUrl;
    }

    /**
     * Subscribe the callback URL for every category we ingest. Best-effort: a
     * failure to subscribe one category is logged and skipped (the refresh
     * sweep still keeps data fresh), never thrown back into the connect flow.
     */
    public void subscribeAll(String accessToken, String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isBlank()) {
            log.warn("Withings callback URL not configured — skipping webhook subscription");
            return;
        }
        for (int appli : SUBSCRIBED_APPLIS) {
            try {
                subscribe(accessToken, callbackUrl, appli);
            } catch (RuntimeException e) {
                log.warn("Withings subscribe appli={} failed: {}", appli, e.getMessage());
            }
        }
    }

    public void subscribe(String accessToken, String callbackUrl, int appli) {
        String body = formEncode(
            "action", "subscribe",
            "callbackurl", callbackUrl,
            "appli", Integer.toString(appli)
        );
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + "/notify"))
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            int status = json.path("status").asInt(-1);
            if (response.statusCode() / 100 != 2 || status != 0) {
                throw new RuntimeException("subscribe status=" + status + " body=" + response.body());
            }
            log.info("Withings subscribed appli={} callback={}", appli, callbackUrl);
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("Withings subscribe call failed", e);
        }
    }

    private static String formEncode(String... pairs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) sb.append('&');
            sb.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
