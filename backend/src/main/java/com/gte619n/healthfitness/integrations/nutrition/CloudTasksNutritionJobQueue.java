package com.gte619n.healthfitness.integrations.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJob;
import com.gte619n.healthfitness.core.nutrition.jobs.NutritionJobQueue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production {@link NutritionJobQueue}: hands each job to a Google Cloud Tasks
 * queue, which persists it and POSTs it back to this service's internal handler
 * ({@code app.nutrition.jobs.cloud-tasks.handler-url}) — retrying with backoff
 * per the queue's own config until the handler returns 2xx. Because the job
 * outlives the enqueuing instance, work is no longer lost when Cloud Run
 * throttles, scales in or redeploys mid-generation (the failure this whole change
 * targets), and the handler runs inside a real request so CPU is guaranteed.
 *
 * <p>Talks to the Cloud Tasks REST API directly with an ADC access token (no
 * extra client dependency). The task carries a shared-secret header the handler
 * verifies, mirroring the Google Health webhook. Enqueue failures are logged, not
 * thrown: the record is already {@code PENDING}/{@code ANALYZING}, so the day-read
 * reconcile sweep re-enqueues it — the backstop that keeps a transient Cloud Tasks
 * hiccup from silently dropping work.
 *
 * <p>Active only when {@code app.nutrition.jobs.mode=cloud-tasks}; otherwise the
 * in-process {@code LocalNutritionJobQueue} is used.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.jobs.mode", havingValue = "cloud-tasks")
public class CloudTasksNutritionJobQueue implements NutritionJobQueue {

    /** Shared-secret header the handler checks; also set by the queue on each task. */
    public static final String SECRET_HEADER = "X-Nutrition-Job-Token";

    private static final Logger log = LoggerFactory.getLogger(CloudTasksNutritionJobQueue.class);

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final GoogleCredentials credentials;
    private final String tasksEndpoint;
    private final String handlerUrl;
    private final String secret;

    public CloudTasksNutritionJobQueue(
        ObjectMapper objectMapper,
        @Value("${app.nutrition.jobs.cloud-tasks.project:${GCP_PROJECT_ID:}}") String project,
        @Value("${app.nutrition.jobs.cloud-tasks.location:us-central1}") String location,
        @Value("${app.nutrition.jobs.cloud-tasks.queue:nutrition-jobs}") String queue,
        @Value("${app.nutrition.jobs.cloud-tasks.handler-url:}") String handlerUrl,
        @Value("${app.nutrition.jobs.secret:}") String secret
    ) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.handlerUrl = handlerUrl;
        // Trim: a Secret Manager value created with `echo` has a trailing newline,
        // which is illegal in an HTTP header value and gets stripped in transit —
        // so the handler (which trims too) would otherwise never see a match.
        this.secret = secret == null ? "" : secret.trim();
        this.tasksEndpoint = String.format(
            "https://cloudtasks.googleapis.com/v2/projects/%s/locations/%s/queues/%s/tasks",
            project, location, queue);
        GoogleCredentials creds;
        try {
            creds = GoogleCredentials.getApplicationDefault()
                .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        } catch (Exception e) {
            // No ADC (e.g. a misconfigured environment): fail soft — enqueue will
            // log and the reconcile sweep remains the backstop.
            log.warn("Cloud Tasks credentials unavailable: {}", e.getMessage());
            creds = null;
        }
        this.credentials = creds;
        if (handlerUrl == null || handlerUrl.isBlank()) {
            log.warn("app.nutrition.jobs.cloud-tasks.handler-url is unset — jobs cannot be delivered");
        }
    }

    @Override
    public void enqueue(NutritionJob job) {
        if (job == null) {
            return;
        }
        try {
            String jobJson = objectMapper.writeValueAsString(job);
            String body = objectMapper.writeValueAsString(Map.of(
                "task", Map.of(
                    "httpRequest", Map.of(
                        "url", handlerUrl,
                        "httpMethod", "POST",
                        "headers", Map.of(
                            "Content-Type", "application/json",
                            SECRET_HEADER, secret),
                        "body", Base64.getEncoder().encodeToString(
                            jobJson.getBytes(StandardCharsets.UTF_8))))));

            HttpRequest request = HttpRequest.newBuilder(URI.create(tasksEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Cloud Tasks enqueue for {} ({}) returned {}: {}",
                    job.type(), job.id(), response.statusCode(),
                    abbreviate(response.body()));
            }
        } catch (Exception e) {
            // Backstop: the record stays PENDING/ANALYZING and the day-read sweep
            // re-enqueues it, so a failed enqueue degrades to delayed, not lost.
            log.warn("Cloud Tasks enqueue failed for {} ({}): {}",
                job.type(), job.id(), e.getMessage());
        }
    }

    private String accessToken() throws Exception {
        if (credentials == null) {
            throw new IllegalStateException("no application default credentials");
        }
        credentials.refreshIfExpired();
        return credentials.getAccessToken().getTokenValue();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
