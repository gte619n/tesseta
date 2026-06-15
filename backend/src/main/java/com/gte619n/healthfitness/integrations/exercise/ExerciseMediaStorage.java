package com.gte619n.healthfitness.integrations.exercise;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.gte619n.healthfitness.core.exercise.DemoPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GCS reads/writes for exercise demo frames. Blobs live at
 * {@code gs://{bucket}/exercises/{exerciseId}/{phase}_{ts}.{ext}}. Versioned
 * names + immutable cache-control mirror {@code EquipmentImageStorage}.
 */
@Component
public class ExerciseMediaStorage {

    private static final Logger log = LoggerFactory.getLogger(ExerciseMediaStorage.class);

    private final Storage storage;
    private final String bucket;

    public ExerciseMediaStorage(Storage storage, @Value("${app.exercises.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    /** Generated frames are always webp (legacy phase-keyed object name). */
    public String upload(String exerciseId, DemoPhase phase, byte[] bytes) {
        return upload(exerciseId, phase, bytes, "image/webp");
    }

    public String upload(String exerciseId, DemoPhase phase, byte[] bytes, String contentType) {
        return uploadKeyed(exerciseId, phase.name(), bytes, contentType);
    }

    /** Generated frames are always webp (plan-key-keyed object name, IMPL-19). */
    public String upload(String exerciseId, String key, byte[] bytes) {
        return uploadKeyed(exerciseId, key, bytes, "image/webp");
    }

    /** Store bytes for an arbitrary plan {@code key} (IMPL-19). */
    public String upload(String exerciseId, String key, byte[] bytes, String contentType) {
        return uploadKeyed(exerciseId, key, bytes, contentType);
    }

    private String uploadKeyed(String exerciseId, String key, byte[] bytes, String contentType) {
        String ext = extensionFor(contentType);
        String safeKey = sanitizeKey(key);
        String objectName = "exercises/" + exerciseId + "/" + safeKey + "_"
            + System.currentTimeMillis() + "." + ext;
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType(contentType)
            .setCacheControl("public, max-age=31536000, immutable")
            .build();
        storage.create(info, bytes);
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }

    private static String sanitizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "frame";
        }
        String s = key.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return s.isBlank() ? "frame" : s;
    }

    public void deleteByUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String prefix = "https://storage.googleapis.com/" + bucket + "/";
        if (!url.startsWith(prefix)) {
            log.warn("Skipping exercise media cleanup — URL does not match bucket prefix: {}", url);
            return;
        }
        String objectName = url.substring(prefix.length());
        int q = objectName.indexOf('?');
        if (q >= 0) {
            objectName = objectName.substring(0, q);
        }
        if (objectName.isBlank()) {
            return;
        }
        try {
            storage.delete(BlobId.of(bucket, objectName));
        } catch (Exception e) {
            log.warn("Failed to delete stale exercise media {}: {}", objectName, e.getMessage());
        }
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return "webp";
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "webp";
        };
    }
}
