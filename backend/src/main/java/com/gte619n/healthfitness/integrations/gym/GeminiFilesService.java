package com.gte619n.healthfitness.integrations.gym;

import com.google.genai.Client;
import com.google.genai.types.DeleteFileConfig;
import com.google.genai.types.File;
import com.google.genai.types.FileState;
import com.google.genai.types.GetFileConfig;
import com.google.genai.types.UploadFileConfig;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * IMPL-GYM-003: thin wrapper over the Gemini Files API. Uploads a video stream,
 * waits for it to become {@code ACTIVE} (video is processed asynchronously), and
 * exposes the file {@code name}/{@code uri} for a {@code generateContent} call.
 * The caller must {@link #delete} the file after use (48 h auto-expiry is only a
 * backstop; the project has a 20 GB Files storage cap).
 */
@Component
@ConditionalOnProperty(name = "app.gym.video-scan.enabled", havingValue = "true", matchIfMissing = false)
public class GeminiFilesService {

    private static final Logger log = LoggerFactory.getLogger(GeminiFilesService.class);
    private static final long POLL_INTERVAL_MS = 2000;

    /** name = server-side handle (for get/delete); uri = reference for Part.fromUri. */
    public record UploadedFile(String name, String uri) {}

    private final Client client;
    private final long timeoutMs;

    public GeminiFilesService(
        Client client,
        @Value("${app.gym.video-scan.files-timeout-ms:120000}") long timeoutMs
    ) {
        this.client = client;
        this.timeoutMs = timeoutMs;
    }

    public UploadedFile uploadAndAwaitActive(InputStream stream, long length, String mimeType) {
        UploadFileConfig cfg = UploadFileConfig.builder().mimeType(mimeType).build();
        File uploaded = client.files.upload(stream, length, cfg);
        String name = uploaded.name()
            .orElseThrow(() -> new IllegalStateException("Files API upload returned no name"));

        long deadline = System.currentTimeMillis() + timeoutMs;
        File current = uploaded;
        while (true) {
            FileState.Known state = current.state()
                .map(FileState::knownEnum).orElse(FileState.Known.STATE_UNSPECIFIED);
            if (state == FileState.Known.ACTIVE) {
                String uri = current.uri()
                    .orElseThrow(() -> new IllegalStateException("active file has no uri"));
                return new UploadedFile(name, uri);
            }
            if (state == FileState.Known.FAILED) {
                throw new IllegalStateException("Files API processing failed for " + name);
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("Files API processing timed out for " + name);
            }
            sleep();
            current = client.files.get(name, GetFileConfig.builder().build());
        }
    }

    public void delete(String name) {
        if (name == null) {
            return;
        }
        try {
            client.files.delete(name, DeleteFileConfig.builder().build());
        } catch (RuntimeException e) {
            log.warn("Failed to delete Files API object {}: {}", name, e.toString());
        }
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting Files API processing", e);
        }
    }
}
