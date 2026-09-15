package com.gte619n.healthfitness.integrations.gym;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.gte619n.healthfitness.core.gym.VideoStore;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * IMPL-GYM-003: GCS-backed {@link VideoStore}. Uploads go DIRECTLY from the client
 * to the bucket via a short-lived V4 signed PUT URL (so a large video never
 * transits Cloud Run); the analysis job streams the object back to hand to the
 * Gemini Files API, then deletes it.
 *
 * <p>A signed PUT is a single-shot upload (fine for the ≤200 MB cap). A true GCS
 * resumable session (better on flaky mobile networks) is a follow-up — see the
 * IMPL-GYM-003 spec.
 */
@Component
@ConditionalOnProperty(name = "app.gym.video-scan.enabled", havingValue = "true", matchIfMissing = false)
public class GymVideoStorage implements VideoStore {

    /** Upload-URL lifetime: long enough to push a few hundred MB on mobile. */
    private static final long UPLOAD_TTL_MINUTES = 30;

    private final Storage storage;
    private final String bucket;

    public GymVideoStorage(Storage storage, @Value("${app.gym.video-scan.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public UploadTarget createUploadTarget(String userId, String locationId, String scanId, String mimeType) {
        String contentType = (mimeType == null || mimeType.isBlank()) ? "video/mp4" : mimeType;
        String objectName = objectName(userId, scanId, contentType);
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType(contentType)
            .build();
        URL url = storage.signUrl(
            info, UPLOAD_TTL_MINUTES, TimeUnit.MINUTES,
            Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
            Storage.SignUrlOption.withContentType(),
            Storage.SignUrlOption.withV4Signature());
        return new UploadTarget(
            objectName, url.toString(), "PUT", Map.of("Content-Type", contentType));
    }

    @Override
    public boolean exists(String objectRef) {
        Blob blob = storage.get(BlobId.of(bucket, objectRef));
        return blob != null && blob.exists();
    }

    @Override
    public long size(String objectRef) {
        Blob blob = storage.get(BlobId.of(bucket, objectRef));
        if (blob == null) {
            throw new IllegalStateException("video object not found: " + objectRef);
        }
        Long s = blob.getSize();
        return s == null ? 0L : s;
    }

    @Override
    public InputStream openStream(String objectRef) {
        Blob blob = storage.get(BlobId.of(bucket, objectRef));
        if (blob == null) {
            throw new IllegalStateException("video object not found: " + objectRef);
        }
        return Channels.newInputStream(blob.reader());
    }

    @Override
    public void delete(String objectRef) {
        storage.delete(BlobId.of(bucket, objectRef));
    }

    private static String objectName(String userId, String scanId, String contentType) {
        String safeUser = (userId == null || userId.isBlank()) ? "unknown" : userId;
        return "gym-scans/" + safeUser + "/" + scanId + "." + extensionFor(contentType);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "video/quicktime" -> "mov";
            default -> "mp4";
        };
    }
}
